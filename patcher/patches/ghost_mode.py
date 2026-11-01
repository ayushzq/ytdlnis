"""Ghost mode: stop Instagram from telling other people what you looked at.

The seen / typing signals are API calls whose path is a string constant. Two techniques:

* "rewrite": the endpoint string is prefixed with "ghost_blocked/", so the request goes to a
  dead path (404). Nothing is nulled, so it is safe for methods returning request objects.
* stub: methods that only SEND typing indicators (void) are replaced by `return-void`.
"""
from .base import Patch, Rule

STORY_SEEN = Patch(
    name="ghost_story_seen",
    description="Do not mark stories/reels as seen.",
    rules=[
        Rule(
            id="story_seen_endpoint",
            description="Redirect the story/reel 'media seen' endpoints to a dead path",
            action="rewrite",
            strings=("media/seen/", "media/%s/seen/", "reels_media_seen", "mark_stories_seen"),
        ),
    ],
)

DM_SEEN = Patch(
    name="ghost_dm_seen",
    description="Do not send read receipts for direct messages.",
    rules=[
        Rule(
            id="dm_seen_endpoint",
            description="Redirect DM / visual-message 'seen' endpoints to a dead path",
            action="rewrite",
            strings=(
                "/items/%s/seen/",
                "mark_visual_item_seen",
                "direct_v2/threads/%s/seen",
                "direct_v2/visual_threads/%s/visual_items/%s/seen/",
                "direct_v2/visual_threads/%s/item_seen/",
            ),
        ),
        Rule(
            id="dm_seen_send_stub",
            description="Void methods that send the DM 'item seen' mutation / mark-thread-seen job -> no-op",
            action="default",
            return_types=("V",),
            strings=("xig_direct_item_seen", "IGDirectItemSeenMutation", "mark_thread_seen-"),
            max_instructions=120,
        ),
    ],
)

_TYPING_RE = r"(?:^|/)indicate_activity/?$"  # exact path only, not log messages

TYPING = Patch(
    name="ghost_typing",
    description="Hide the typing indicator in direct messages.",
    rules=[
        Rule(
            id="typing_send_stub",
            description="Void/boolean methods that send the typing indicator -> no-op",
            action="default",
            return_types=("V", "Z"),
            string_regex=_TYPING_RE,
            prefilter=("indicate_activity",),
        ),
        Rule(
            id="typing_endpoint_rewrite",
            description="Any other user of the typing endpoint -> dead path",
            action="rewrite",
            string_regex=_TYPING_RE,
            prefilter=("indicate_activity",),
        ),
    ],
)

PATCH = Patch(
    name="ghost_mode",
    description="Ghost mode: hide story seen, DM seen and typing indicators.",
    rules=STORY_SEEN.rules + DM_SEEN.rules + TYPING.rules,
)

PATCHES = [PATCH, STORY_SEEN, DM_SEEN, TYPING]
