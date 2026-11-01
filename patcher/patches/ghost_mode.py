"""Ghost mode: stop Instagram from telling other people what you looked at.

The seen / typing signals are plain API calls whose URL path is a string constant,
so the rules locate the method that references that path and neutralise it.
Methods returning objects are only touched with --allow-null-returns because
returning null from them can crash callers; they are always listed in the report.
"""
from .base import Patch, Rule

STORY_SEEN = Patch(
    name="ghost_story_seen",
    description="Do not mark stories/reels as seen.",
    rules=[
        Rule(
            id="story_seen_endpoint",
            description="Request code for the story 'media seen' endpoints",
            action="default",
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
            description="Request code for DM 'seen' endpoints",
            action="default",
            strings=("/items/%s/seen/", "mark_visual_item_seen", "direct_v2/threads/%s/seen"),
        ),
    ],
)

TYPING = Patch(
    name="ghost_typing",
    description="Hide the typing indicator in direct messages.",
    rules=[
        Rule(
            id="typing_indicator_endpoint",
            description="Request code for the DM 'indicate_activity' (typing) endpoint",
            action="default",
            strings=("indicate_activity",),
        ),
    ],
)

PATCH = Patch(
    name="ghost_mode",
    description="Ghost mode: hide story seen, DM seen and typing indicators.",
    rules=STORY_SEEN.rules + DM_SEEN.rules + TYPING.rules,
)

PATCHES = [PATCH, STORY_SEEN, DM_SEEN, TYPING]
