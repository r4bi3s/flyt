#!/usr/bin/env python3
"""Push Flyt's store listing (fastlane/metadata/android) to Play Console as a draft edit.

Text (title, short and full description) and images (icon, feature graphic, phone screenshots) for every
locale folder. Does not touch tracks, releases, prices or app content. Uses the maintainer's gcloud login to
impersonate the Play service account; no key file. Dry run by default:

    release/play-listing.py           # show what would be sent
    release/play-listing.py --apply   # send it and commit the edit

Run it yourself on Chilibot. The service account needs access to Flyt under Users and permissions.
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
META = ROOT / "fastlane/metadata/android"
PACKAGE = "no.heimflyt.launcher"
SERVICE_ACCOUNT = "lunni-play-publisher@lunni-play-publishing.iam.gserviceaccount.com"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}


def text(locale: Path, name: str) -> str:
    value = (locale / name).read_text(encoding="utf-8").rstrip("\n")
    if len(value) > LIMITS[name]:
        sys.exit(f"{locale.name}/{name} is {len(value)} characters; Play allows {LIMITS[name]}.")
    return value


def plan():
    for locale in sorted(p for p in META.iterdir() if p.is_dir()):
        images = locale / "images"
        shots = sorted((images / "phoneScreenshots").glob("*.png"))
        yield locale.name, {
            "title": text(locale, "title.txt"),
            "shortDescription": text(locale, "short_description.txt"),
            "fullDescription": text(locale, "full_description.txt"),
        }, [("icon", images / "icon.png"), ("featureGraphic", images / "featureGraphic.png")], shots


def token() -> str:
    mine = subprocess.run(["gcloud", "auth", "print-access-token"], capture_output=True, text=True, check=True).stdout.strip()
    req = urllib.request.Request(
        f"https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/{SERVICE_ACCOUNT}:generateAccessToken",
        data=json.dumps({"scope": ["https://www.googleapis.com/auth/androidpublisher"]}).encode(),
        headers={"Authorization": f"Bearer {mine}", "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)["accessToken"]


def call(tok: str, method: str, url: str, data=None, raw=None, content_type="application/json"):
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"Bearer {tok}")
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as resp:
            raw_resp = resp.read()
            return json.loads(raw_resp) if raw_resp else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"ERROR {method} {url}\n{e.read().decode()}")


def main() -> None:
    apply = "--apply" in sys.argv[1:]
    items = list(plan())
    for lang, listing, images, shots in items:
        print(f"{lang}: title «{listing['title']}», short {len(listing['shortDescription'])} chars, "
              f"full {len(listing['fullDescription'])} chars, icon, feature graphic, {len(shots)} screenshots")
    if not apply:
        print("\nDry run. Nothing was sent. Run again with --apply to update the draft listing in Play Console.")
        return

    tok = token()
    edit = call(tok, "POST", f"{BASE}/edits", data={})["id"]
    print(f"Edit {edit} opened.")
    for lang, listing, images, shots in items:
        call(tok, "PUT", f"{BASE}/edits/{edit}/listings/{lang}", data={"language": lang, **listing})
        for kind, path in images:
            call(tok, "POST", f"{UPLOAD}/edits/{edit}/listings/{lang}/{kind}?uploadType=media", raw=path.read_bytes(), content_type="image/png")
        call(tok, "DELETE", f"{BASE}/edits/{edit}/listings/{lang}/phoneScreenshots")
        for shot in shots:
            call(tok, "POST", f"{UPLOAD}/edits/{edit}/listings/{lang}/phoneScreenshots?uploadType=media", raw=shot.read_bytes(), content_type="image/png")
        print(f"  {lang}: text, icon, feature graphic and {len(shots)} screenshots sent")
    call(tok, "POST", f"{BASE}/edits/{edit}:commit", data={})
    print(f"Committed edit {edit}. The store listing is saved in Play Console; nothing was published or submitted.")


if __name__ == "__main__":
    main()
