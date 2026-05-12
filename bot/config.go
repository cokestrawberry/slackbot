package main

import (
	"fmt"
	"os"
	"strings"
	"time"
)

type Config struct {
	Port           string
	SpringURL      string
	SigningSecret  string
	BotToken       string
	ForwardTimeout time.Duration
}

func LoadConfig() (Config, error) {
	cfg := Config{
		Port:           envOr("PORT", "3000"),
		SpringURL:      envOr("SPRING_EVENT_URL", "http://localhost:8080/api/slack/event"),
		SigningSecret:  os.Getenv("SLACK_SIGNING_SECRET"),
		BotToken:       os.Getenv("SLACK_BOT_TOKEN"),
		ForwardTimeout: 5 * time.Second,
	}

	// Signing secret / bot token are not used by the proxy itself today, but
	// validate presence so misconfiguration surfaces at startup rather than
	// after the first Slack event. BotToken is allowed to be empty for now —
	// reserved for future outbound Slack API calls (reactions, replies, etc.).
	if strings.TrimSpace(cfg.SigningSecret) == "" {
		return Config{}, fmt.Errorf("SLACK_SIGNING_SECRET is required")
	}
	if !strings.HasPrefix(cfg.SpringURL, "http://") && !strings.HasPrefix(cfg.SpringURL, "https://") {
		return Config{}, fmt.Errorf("SPRING_EVENT_URL must be an http(s) URL, got %q", cfg.SpringURL)
	}
	return cfg, nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
