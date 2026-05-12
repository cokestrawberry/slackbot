package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

var errUpstreamRejected = errors.New("spring boot rejected the request")

// Forwarder proxies Slack events to the Spring Boot server.
//
// Option (a) from the phase1 spec: raw body + Slack signature headers are
// passed through untouched so Spring Boot can validate the HMAC itself. The
// bot does not re-sign requests.
type Forwarder struct {
	target string
	client *http.Client
	logger *slog.Logger
}

func NewForwarder(target string, client *http.Client, logger *slog.Logger) *Forwarder {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	return &Forwarder{target: target, client: client, logger: logger}
}

// Forward POSTs body to the Spring Boot endpoint. On transient failures
// (network errors, 5xx) it retries exactly once with a short backoff.
// 4xx from Spring is considered permanent and is returned as errUpstreamRejected.
func (f *Forwarder) Forward(ctx context.Context, body []byte, src http.Header) error {
	const maxAttempts = 2
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		err := f.send(ctx, body, src)
		if err == nil {
			return nil
		}
		if errors.Is(err, errUpstreamRejected) {
			return err
		}
		lastErr = err
		if attempt == maxAttempts {
			break
		}
		f.logger.Warn("forward attempt failed, retrying", "attempt", attempt, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
	return lastErr
}

func (f *Forwarder) send(ctx context.Context, body []byte, src http.Header) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.target, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	// Preserve Slack's original headers so Spring Boot can re-validate HMAC
	// against the untouched body. Copy only the headers we actually need —
	// hop-by-hop headers (Connection, Host, etc.) must not be forwarded.
	copyHeader(req.Header, src, "Content-Type", "X-Slack-Signature", "X-Slack-Request-Timestamp", "X-Slack-Retry-Num", "X-Slack-Retry-Reason")
	if req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := f.client.Do(req)
	if err != nil {
		return fmt.Errorf("post to spring: %w", err)
	}
	defer resp.Body.Close()

	// Drain small responses so keep-alive connections can be reused.
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	switch {
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		return nil
	case resp.StatusCode >= 500:
		return fmt.Errorf("spring returned %d (transient)", resp.StatusCode)
	default:
		return fmt.Errorf("%w: %d", errUpstreamRejected, resp.StatusCode)
	}
}

func copyHeader(dst, src http.Header, keys ...string) {
	for _, k := range keys {
		if v := src.Get(k); v != "" {
			dst.Set(k, v)
		}
	}
}
