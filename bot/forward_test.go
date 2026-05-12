package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestForward_SuccessPassesHeadersAndBody(t *testing.T) {
	const body = `{"type":"event_callback","event":{"type":"message","text":"hi"}}`
	var received struct {
		method    string
		sig       string
		ts        string
		ctype     string
		body      []byte
		hostHeader string
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received.method = r.Method
		received.sig = r.Header.Get("X-Slack-Signature")
		received.ts = r.Header.Get("X-Slack-Request-Timestamp")
		received.ctype = r.Header.Get("Content-Type")
		received.hostHeader = r.Header.Get("Host")
		b, _ := io.ReadAll(r.Body)
		received.body = b
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	f := NewForwarder(srv.URL, srv.Client(), testLogger())
	src := http.Header{}
	src.Set("Content-Type", "application/json")
	src.Set("X-Slack-Signature", "v0=deadbeef")
	src.Set("X-Slack-Request-Timestamp", "1700000000")
	src.Set("Connection", "keep-alive") // hop-by-hop — must NOT be forwarded
	src.Set("Host", "tunnel.example.com")

	if err := f.Forward(context.Background(), []byte(body), src); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if received.method != http.MethodPost {
		t.Errorf("method = %q, want POST", received.method)
	}
	if received.sig != "v0=deadbeef" {
		t.Errorf("X-Slack-Signature = %q, want v0=deadbeef", received.sig)
	}
	if received.ts != "1700000000" {
		t.Errorf("X-Slack-Request-Timestamp = %q, want 1700000000", received.ts)
	}
	if received.ctype != "application/json" {
		t.Errorf("Content-Type = %q", received.ctype)
	}
	if !bytes.Equal(received.body, []byte(body)) {
		t.Errorf("body mismatch: got %q, want %q", received.body, body)
	}
	if received.hostHeader != "" {
		t.Errorf("Host header must not be forwarded as a regular header, got %q", received.hostHeader)
	}
}

func TestForward_RetriesOnce_OnTransient5xx(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := calls.Add(1)
		if n == 1 {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	f := NewForwarder(srv.URL, srv.Client(), testLogger())
	if err := f.Forward(context.Background(), []byte(`{}`), http.Header{}); err != nil {
		t.Fatalf("unexpected error after retry: %v", err)
	}
	if got := calls.Load(); got != 2 {
		t.Errorf("expected exactly 2 calls (1 fail + 1 retry), got %d", got)
	}
}

func TestForward_NoRetryOn4xx(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	f := NewForwarder(srv.URL, srv.Client(), testLogger())
	err := f.Forward(context.Background(), []byte(`{}`), http.Header{})
	if !errors.Is(err, errUpstreamRejected) {
		t.Fatalf("expected errUpstreamRejected, got %v", err)
	}
	if got := calls.Load(); got != 1 {
		t.Errorf("4xx should not retry; got %d calls", got)
	}
}

func TestForward_RetriesOnce_OnConnectionError(t *testing.T) {
	// Target a closed listener to trigger a real connection error.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("cannot open listener: %v", err)
	}
	addr := "http://" + ln.Addr().String()
	_ = ln.Close() // immediately close so connections fail

	f := NewForwarder(addr, &http.Client{Timeout: 500 * time.Millisecond}, testLogger())
	start := time.Now()
	err = f.Forward(context.Background(), []byte(`{}`), http.Header{})
	if err == nil {
		t.Fatalf("expected error for closed target")
	}
	// Two attempts + one 250ms backoff means elapsed should exceed backoff.
	if elapsed := time.Since(start); elapsed < 200*time.Millisecond {
		t.Errorf("expected backoff between attempts; elapsed=%v", elapsed)
	}
}

func TestHandler_URLVerificationEchoesChallenge(t *testing.T) {
	// Forwarder with a target that would fail if hit — proves handler does not forward.
	f := NewForwarder("http://127.0.0.1:1", &http.Client{Timeout: 500 * time.Millisecond}, testLogger())
	h := NewHandler(f, testLogger())

	req := httptest.NewRequest(http.MethodPost, "/slack/events",
		bytes.NewReader([]byte(`{"type":"url_verification","challenge":"abc123"}`)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"challenge":"abc123"`)) {
		t.Errorf("response body missing challenge echo: %s", rec.Body.String())
	}
}

func TestHandler_ForwardsEventCallback(t *testing.T) {
	var got []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	h := NewHandler(NewForwarder(srv.URL, srv.Client(), testLogger()), testLogger())
	payload := []byte(`{"type":"event_callback","event":{"type":"message"}}`)
	req := httptest.NewRequest(http.MethodPost, "/slack/events", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Slack-Signature", "v0=abc")
	req.Header.Set("X-Slack-Request-Timestamp", "1700000000")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if !bytes.Equal(got, payload) {
		t.Errorf("forwarded body mismatch: got %q, want %q", got, payload)
	}
}
