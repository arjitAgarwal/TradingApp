#!/usr/bin/env python3
"""Generate today's FYERS access token ON YOUR OWN COMPUTER, so the app secret never goes into the APK.

    pip install fyers-apiv3
    python tools/fyers_token.py          # prompts for app id, secret, redirect uri
Open the printed URL, log in, copy the `auth_code` value from the redirected URL, paste it back.
Then paste the printed access token (not the secret) into the app: Account -> Market data.
Tokens expire daily around 06:30 IST. Follows FYERS' documented flow; verify against https://fyers.in/api-docs/v3/.
"""
import getpass
from fyers_apiv3 import fyersModel

app_id = input("App ID (e.g. ABCD1234-100): ").strip()
secret = getpass.getpass("App secret (not stored): ").strip()
redirect = input("Redirect URI registered for the app: ").strip()

session = fyersModel.SessionModel(client_id=app_id, secret_key=secret, redirect_uri=redirect,
                                  response_type="code", grant_type="authorization_code")
print("\nOpen this URL and log in:\n" + session.generate_authcode())
session.set_token(input("\nPaste auth_code from the redirected URL: ").strip())
resp = session.generate_token()
if resp.get("s") != "ok":
    raise SystemExit(f"Token request failed: {resp}")
print("\nACCESS TOKEN (paste into the app, valid until ~06:30 IST tomorrow):\n" + resp["access_token"])
try:  # Windows: also put it on the clipboard, since selecting a long token in the console is error-prone
    import subprocess
    subprocess.run(["clip"], input=resp["access_token"], text=True, check=True)
    print(f"\n(Copied to clipboard: {len(resp['access_token'])} characters)")
except Exception:
    pass
