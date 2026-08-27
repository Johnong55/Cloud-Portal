# Production release checklist

## 1. Create and protect the upload key

Generate one long-lived upload key and keep at least two encrypted backups outside the repository. Never commit the keystore or passwords.

Copy `keystore.properties.example` to `keystore.properties`, then replace every placeholder. CI can instead provide these environment variables:

- `CLOUD_PORTAL_STORE_FILE`
- `CLOUD_PORTAL_STORE_PASSWORD`
- `CLOUD_PORTAL_KEY_ALIAS`
- `CLOUD_PORTAL_KEY_PASSWORD`

## 2. Run the production gate

```bash
./gradlew clean productionCheck
```

This runs unit tests, release lint, R8/resource shrinking, creates the release AAB and refuses to pass without a signing key.

The signed bundle is generated at:

```text
app/build/outputs/bundle/release/app-release.aab
```

Archive `mapping.txt` from `app/build/outputs/mapping/release/` with every published version.

## 3. Play Console

- Enroll in Play App Signing and upload the signed AAB with the upload key.
- Publish `PRIVACY.md` at a public URL and add it as the privacy-policy URL.
- Complete Data safety accurately: no developer collection, while files and credentials are processed locally/on Apple services to provide app functionality.
- Complete content rating, app access instructions and the declaration that Cloud Portal is independent from Apple.
- Start with Internal testing, then Closed testing, before Production.
- Verify login, 2FA, Photos, Drive, Notes, single and multi-file downloads on at least Android 10 and the latest Android version.
