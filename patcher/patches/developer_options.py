"""Employee mode: make Instagram's own "is this an employee / internal user" gate return true.

Found in a real decode (build 455): LX/02ss;->A00(UserSession)Z is called right next to the checks of the
developer-options module (LX/0C73 / LX/04aX.A0K) and of MobileConfig debug overrides. The heuristic string
rules used before matched nothing, so this one pins the method. It is wrapped in the Employee-mode toggle of
the in-app menu (off by default); the pinned names only exist in that build, other builds simply skip it.
"""
from .base import Patch, Rule

PATCH = Patch(
    name="developer_options",
    description="Employee mode: internal-user gate -> true while the toggle is on (pinned to build 455).",
    rules=[
        Rule(
            id="employee_gate",
            description="LX/02ss;->A00(UserSession)Z (internal / employee check) returns true",
            action="true",
            feature="devopt",
            return_types=("Z",),
            target=("LX/02ss;", "A00(Lcom/instagram/common/session/UserSession;)Z"),
        ),
    ],
)
