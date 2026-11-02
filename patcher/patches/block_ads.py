"""Block sponsored content.

Layers, all anchored on string constants:
  0. The Reels ad inserter's "meets min gap" check is forced to false, so ads are not put in the list at all.
  1. Ad-delivery endpoints (e.g. story ad injection) are redirected to a dead path.
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
            feature="ads",
            description="Redirect ad-delivery endpoints (story ad injection, ads GraphQL) to a dead path",
            action="rewrite",
            strings=("feed/injected_reels_media/", "ads/graphql/"),
        ),
        Rule(
            id="reels_ad_insertion_gap",
            description="Ad inserter 'sponsored item meets min gap' check -> false: no sponsored item is ever inserted into the Reels list",
            action="false",
            feature="ads",
            return_types=("Z",),
            strings=("sponsored_item_meets_min_gap",),
            max_instructions=1500,
        ),
        Rule(
            id="ad_feature_flags",
            feature="ads",
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
