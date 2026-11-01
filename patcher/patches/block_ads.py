"""Block sponsored content.

Two layers, both anchored on string constants:
  1. Ad-delivery endpoints (e.g. story ad injection) are neutralised.
  2. Boolean feature-flag helpers for feed/stories/reels ad loading return false.
Server-side ad insertion changes often; check the report to see what matched.
"""
from .base import Patch, Rule

PATCH = Patch(
    name="block_ads",
    description="Block sponsored posts / story ads (ad endpoints + ad feature flags).",
    rules=[
        Rule(
            id="ad_endpoints",
            description="Requests that inject sponsored content into stories / feed",
            action="default",
            strings=("feed/injected_reels_media/", "ads/graphql/", "feed/ads/"),
        ),
        Rule(
            id="ad_feature_flags",
            description="Boolean helpers reading ig_android_*ad* MobileConfig flags",
            action="false",
            return_types=("Z",),
            string_regex=r"^ig_android_(?:feed|stories|story|reels?|explore|clips)\w*(?:_ads?_|_ads?$|sponsored|ad_load|ad_insert)\w*$",
            prefilter=("ig_android_feed", "ig_android_stories", "ig_android_story", "ig_android_reel", "ig_android_explore", "ig_android_clips"),
            max_instructions=25,
            max_params=2,
        ),
    ],
)
