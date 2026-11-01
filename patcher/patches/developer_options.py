"""Unlock the internal Developer Options / MobileConfig menu.

Instagram hides its employee-only tooling behind boolean gates ("is this user an
employee / internal build?"). Class and method names are obfuscated and change on
every release, so the rules key on stable *string constants* and *field names*
instead and force the surrounding boolean helper to return true.

If a release stops matching, decode it, `grep -rn "is_employee" decoded/smali*`,
find the gate and extend the strings below.
"""
from .base import Patch, Rule

PATCH = Patch(
    name="developer_options",
    description="Unlock the internal Developer Options / MobileConfig menu (employee/internal gates -> true).",
    rules=[
        Rule(
            id="employee_flag_string",
            description="Boolean helpers keyed on 'is_employee' / 'is_internal' style strings",
            action="true",
            return_types=("Z",),
            strings=("is_employee", "is_ig_employee", "is_internal", "internal_user"),
            max_instructions=40,
            max_params=2,
        ),
        Rule(
            id="employee_flag_field",
            description="Boolean getters reading an isEmployee/isInternal style field",
            action="true",
            return_types=("Z",),
            field_regex=r"(?i)->(?:is_?(?:ig_?)?(?:employee|internal)\w*):Z$",
            prefilter=("isEmployee", "is_employee", "isInternal", "is_internal", "isIgEmployee"),
            max_instructions=20,
            max_params=1,
        ),
        Rule(
            id="dev_menu_gate",
            description="Boolean helpers that gate the dev-options / internal-settings menu entry",
            action="true",
            return_types=("Z",),
            string_regex=r"(?i)^(?:internal[ _]?settings|dev(?:eloper)?[ _]?options|mobile[ _]?config[ _]?(?:override)?s?)$",
            prefilter=(
                "Internal Settings", "internal_settings", "InternalSettings",
                "developer_options", "Developer Options", "Developer options",
                "dev_options", "Dev Options", "MobileConfig", "mobileconfig",
            ),
            max_instructions=30,
            max_params=2,
        ),
    ],
)
