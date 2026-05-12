# 04. 공개 URL 준비 (ngrok)

ngrok 바이너리는 이미 설치되어 있습니다 (`ngrok 3.37.6`).
남은 작업은 authtoken 1회 등록 + 실행뿐입니다.

## 왜 필요?

Slack → Event 은 퍼블릭 HTTPS 로만 보낼 수 있습니다. 로컬 `:3000` (Go bot) 을
외부에 노출할 수단이 필요.

## authtoken 등록 (1회)

```bash
# 이미 등록했으면 스킵
ngrok config check 2>&1 || true
```

안 되어 있다면:

1. https://ngrok.com 가입 (GitHub/Google 로그인)
2. Dashboard → Your Authtoken 복사
3. `ngrok config add-authtoken <TOKEN>`

## 실행

```bash
ngrok http 3000
```

출력:
```
Forwarding  https://abcd-1234.ngrok-free.app -> http://localhost:3000
```

이 URL 에 `/slack/events` 를 붙여 **Slack Event Subscriptions Request URL** 로 등록:
```
https://abcd-1234.ngrok-free.app/slack/events
```

> 무료 플랜은 재시작마다 URL 이 변경됨. 고정 도메인 원하면 유료 플랜 or Cloudflare Tunnel 고려.

## 네트워크 흐름

```
Slack ──HTTPS──▶ https://abcd-1234.ngrok-free.app/slack/events
                 └── ngrok ──▶ localhost:3000  (Go bot)
                                  └── http://localhost:8080/api/slack/event  (Spring)
                                        ├── HMAC 검증
                                        ├── Claude CLI 서브프로세스 호출
                                        └── Jira API 호출 (외부)
```
