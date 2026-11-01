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
            id="dm_mark_thread_seen_stub",
            description="Void method that enqueues the 'mark thread seen' task -> no-op",
            action="default",
            return_types=("V",),
            strings=("mark_thread_seen-",),
            max_instructions=3000,
        ),
        Rule(
            id="dm_item_seen_mutation_abort",
            description="IGDirectItemSeenMutation executor: take the method's own error exit before sending (pinned to v455 code)",
            action="inject",
            return_types=("V",),
            strings=("IGDirectItemSeenMutation",),
            max_instructions=3000,
            require=(
                "xig_direct_item_seen",
                "LX/0ntB;->Fu8(LX/0FXy;Ljava/lang/String;)V",
                "LX/0AEJ;->A00(LX/02qp;Ljava/lang/Integer;)V",
                "LX/0FXy;->A0U:LX/0FXy;",
                "LX/000B;->A01:Ljava/lang/Integer;",
            ),
            inject=(
                "sget-object v0, LX/0FXy;->A0U:LX/0FXy;",
                "const/4 v1, 0x0",
                "move-object/from16 v2, p2",
                "invoke-interface {v2, v0, v1}, LX/0ntB;->Fu8(LX/0FXy;Ljava/lang/String;)V",
                "sget-object v0, LX/000B;->A01:Ljava/lang/Integer;",
                "move-object/from16 v2, p1",
                "invoke-static {v2, v0}, LX/0AEJ;->A00(LX/02qp;Ljava/lang/Integer;)V",
                "return-void",
            ),
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
