# AI Phone Agent

Android automation agent for a Samsung Galaxy S26 Ultra and compatible Android devices.

## Current V1

- Native Kotlin Android app.
- Accessibility Service for authorized UI inspection and interaction.
- Natural-language command parser for a small set of safe commands.
- Open Facebook.
- Open the SMS/messaging application.
- Click visible UI text when the accessibility service is enabled.
- Emergency STOP button.
- Local action log.
- SMS receive logging.
- SIM-based SMS sending component for future UI integration.
- GitHub Actions automatically builds a debug APK.

## Important permissions

The user must explicitly enable the Accessibility Service in Android Settings. SMS permissions are requested at runtime. The app does not bypass passwords, 2FA, CAPTCHA, identity checks, or application security controls.

## Build

GitHub Actions builds `app/build/outputs/apk/debug/app-debug.apk` and uploads it as the `AI-Phone-Agent-debug` artifact.

## Roadmap

1. Verify V1 on the Galaxy S26 Ultra.
2. Add secure backend/API authentication.
3. Add OpenAI integration without embedding API secrets in the APK.
4. Add HubSpot OAuth/API integration.
5. Add task planning, confirmations, retries, and audit controls.
6. Add additional app integrations subject to their platform rules.
