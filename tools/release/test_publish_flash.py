"""Regression for GitHub's temporary draft asset URL, observed during first publication."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("publish_flash", Path(__file__).with_name("publish-flash.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class DraftAssetTest(unittest.TestCase):
    def payload(self, draft=True):
        return {"tag_name": "v0.1.39", "draft": draft, "prerelease": False,
                "target_commitish": "a" * 40,
                "assets": [{"name": "FlashPhone-v0.1.39.apk", "size": 123,
                            "state": "uploaded", "digest": "sha256:" + "b" * 64,
                            "browser_download_url": "https://github.com/DzsGatsby/Mika/releases/download/untagged-ab12/FlashPhone-v0.1.39.apk"}]}

    def verify(self, payload, draft=True):
        release.verify_release(payload, "v0.1.39", "a" * 40, "FlashPhone-v0.1.39.apk", 123, "b" * 64, draft=draft)

    def test_draft_accepts_temporary_url_for_exact_asset(self):
        self.verify(self.payload())

    def test_public_release_requires_final_tag_url(self):
        p = self.payload(False)
        with self.assertRaises(release.ReleaseError):
            self.verify(p, False)
        p["assets"][0]["browser_download_url"] = p["assets"][0]["browser_download_url"].replace("untagged-ab12", "v0.1.39")
        self.verify(p, False)

    def test_draft_rejects_other_repository(self):
        p = self.payload()
        p["assets"][0]["browser_download_url"] = p["assets"][0]["browser_download_url"].replace("DzsGatsby", "other")
        with self.assertRaises(release.ReleaseError):
            self.verify(p)

    def test_draft_still_rejects_digest_mismatch(self):
        p = self.payload()
        p["assets"][0]["digest"] = "sha256:" + "c" * 64
        with self.assertRaises(release.ReleaseError):
            self.verify(p)


if __name__ == "__main__":
    unittest.main()
