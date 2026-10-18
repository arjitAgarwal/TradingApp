#!/usr/bin/env python3
"""Check a FYERS access token against the same endpoints the app uses. Prints FYERS' own responses, never the token.

    py -3.13 tools/fyers_check.py
"""
import subprocess
import requests

app_id = input("App ID (e.g. ABCD1234-100): ").strip().lstrip("﻿")
input("Copy the access token to the clipboard, then press Enter...")
# Pasting into a hidden console prompt is unreliable on Windows, so read the clipboard directly.
token = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-Clipboard"], capture_output=True, text=True).stdout.strip()
print(f"\nApp ID: {app_id!r}  |  token length: {len(token)}, parts: {token.count('.') + 1}")

# The middle part of a JWT is readable (not secret): it says what kind of token this is and when it expires.
import base64, datetime, json
try:
    part = token.split(".")[1]; part += "=" * (-len(part) % 4)
    claims = json.loads(base64.urlsafe_b64decode(part))
    ist = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
    when = lambda s: datetime.datetime.fromtimestamp(s, ist).strftime("%d %b %H:%M IST") if isinstance(s, (int, float)) else s
    print(f"Token type (sub): {claims.get('sub')}  |  app_id inside: {claims.get('app_id')}")
    print(f"Issued: {when(claims.get('iat'))}  |  Expires: {when(claims.get('exp'))}  |  Now: {datetime.datetime.now(ist).strftime('%d %b %H:%M IST')}")
    if claims.get("sub") == "auth_code":
        print(">>> This is the AUTH CODE from the browser, not the access token. Use what fyers_token.py prints at the end.")
except Exception as e:
    print(f"Could not read token details ({e}) - this is probably not a FYERS token at all.")

headers = {"Authorization": f"{app_id}:{token}"}
checks = [
    ("Profile (token validity)", "https://api-t1.fyers.in/api/v3/profile"),
    ("Quotes (what the app polls)", "https://api-t1.fyers.in/data/quotes?symbols=NSE:NIFTY50-INDEX,BSE:SENSEX-INDEX"),
]
for name, url in checks:
    try:
        r = requests.get(url, headers=headers, timeout=15)
        print(f"\n{name}: HTTP {r.status_code}\n{r.text[:600]}")
    except requests.RequestException as e:
        print(f"\n{name}: network error: {e}")
