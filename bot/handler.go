package main

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"github.com/slack-go/slack/slackevents"
)

const maxBodyBytes = 1 << 20 // 1 MiB — Slack events never come close

// Handler receives Slack Events API callbacks.
//
// URL verification is answered locally. Everything else (event_callback, etc.)
// is forwarded as-is to the Spring Boot server so that Spring can re-validate
// the X-Slack-Signature against the untouched raw body.
type Handler struct {
	forwarder *Forwarder
	logger    *slog.Logger
}

func NewHandler(forwarder *Forwarder, logger *slog.Logger) *Handler {
	return &Handler{forwarder: forwarder, logger: logger}
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		h.logger.Warn("failed to read request body", "err", err)
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	// STUDY(slack-go): slackevents.ParseEvent distinguishes url_verification
	// from event_callback envelopes. OptionNoVerifyToken skips the deprecated
	// verification-token check — we still rely on Spring Boot's HMAC check.
	parsed, err := slackevents.ParseEvent(json.RawMessage(body), slackevents.OptionNoVerifyToken())
	if err != nil {
		h.logger.Warn("invalid slack event envelope", "err", err)
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	if parsed.Type == slackevents.URLVerification {
		var challenge slackevents.ChallengeResponse
		if err := json.Unmarshal(body, &challenge); err != nil {
			h.logger.Warn("url_verification parse failed", "err", err)
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		h.logger.Info("url_verification challenge echoed")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"challenge": challenge.Challenge})
		return
	}

	if err := h.forwarder.Forward(r.Context(), body, r.Header); err != nil {
		if errors.Is(err, errUpstreamRejected) {
			h.logger.Error("upstream rejected event", "err", err)
			http.Error(w, "upstream rejected", http.StatusBadGateway)
			return
		}
		h.logger.Error("forwarding failed", "err", err)
		http.Error(w, "forwarding failed", http.StatusBadGateway)
		return
	}

	h.logger.Info("event forwarded",
		"envelope_type", parsed.Type,
		"inner_type", parsed.InnerEvent.Type)
	w.WriteHeader(http.StatusOK)
}
