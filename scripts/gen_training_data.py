#!/usr/bin/env python3
"""Synthetic function-calling data generator for the fallback LLM.

Why this exists
---------------
The deterministic fast path already handles 8 of the 17 tools, so the model only
ever sees the residual. Training on the whole schema would spend most of the
data teaching the model things that never reach it, and would actively harm it:
`set_flashlight` has a trivially rule-based surface, so every synthetic example
of it is a gradient step spent on an already-solved behaviour.

So the default output is LLM-territory only. Fast-path utterances are generated
too, but tagged `fast`, and excluded unless `--include-fastpath` is passed. They
exist so the eval harness can prove that the router keeps them away from the
model.

Design rules enforced by construction (and re-checked by `voice_data`):

* Slots named `*_ref`, `query`, `app_name`, `alias` get the raw spoken string.
  The generator has no access to a phone book and no way to emit a number; a
  validator rejects digit runs that look like phone numbers.
* Negations never produce a tool call. "don't turn on wifi" must not become
  `toggle_setting(state=on)`; the model is taught to `unsupported` instead.
* A missing required slot produces `ask_clarification`, never a partial call.
  A half-filled call is the failure mode most likely to execute the wrong thing.
* An out-of-enum slot value produces `ask_clarification` rather than
  inventing a value. Clamping silently is the bug the fast path tests guard
  against, and the model must learn the same discipline.
* Output is deterministic for a given `--seed`, so a data change is always
  attributable to the template bank rather than to luck.

Usage
-----
    scripts/gen_training_data.py --out data/
    scripts/gen_training_data.py --out data/ --include-fastpath --per-tool 60
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Any, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))

from voice_data import (  # noqa: E402
    REPO_ROOT,
    TAG_AMBIGUOUS,
    TAG_FASTPATH,
    TAG_LLM,
    TAG_MISSING_SLOT,
    TAG_NEGATION,
    TAG_OUT_OF_DOMAIN,
    TAG_OUTSIDE_ENUM,
    PROSE_SLOTS,
    ToolCatalog,
    ValidationError,
    assign_ids,
    load_catalog,
    make_example,
    write_jsonl,
)

# ---------------------------------------------------------------------------
# Slot fillers
# ---------------------------------------------------------------------------

# Indian-first names, because Double Metaphone and the FTS index were tuned on
# this failure class: STT routinely renders "Rohan" as "Rohan"/"Rohit"/"Rowan".
NAMES = [
    "Rohan", "Priya", "Amit", "Sneha", "Rahul", "Ananya", "Vikram", "Divya",
    "Arjun", "Kavya", "Rohit", "Meera", "Aditya", "Nisha", "Sanjay", "Pooja",
    "Manish", "Anjali", "Karan", "Shreya", "bunty", "Rinky", "Sunita", "Deepak",
]

# Relation words the resolver resolves through the relation chain. Kept separate
# from NAMES so an example can tag "said a relation, not a name" honestly.
RELATIONS = [
    "my wife", "my husband", "my mom", "my dad", "my brother", "my sister",
    "my friend", "my boss", "my sister in law", "my mother", "my father",
]

APPS = [
    "spotify", "whatsapp", "instagram", "youtube", "maps", "camera", "chrome",
    "gmail", "telegram", "netflix", "amazon", "swiggy", "zomato", "uber",
    "calculator", "clock", "settings", "play store", "phone", "contacts",
]

TRACKS = [
    "believer", "blinding lights", "arashiva", "Shape of You", "bohemian rhapsody",
    "despacito", " Kesha", "radioactive", "wedgwood", "Tum Hi Ho",
]
ARTISTS = ["Arijit Singh", "Taylor Swift", "BTS", "A.R. Rahman", "Ed Sheeran", "Neha Kakkar", "Coldplay"]
ALBUMS = ["thriller", "songs from the heart", "A night at the opera", "Divide", "Mohenjo Daro"]
PLAYLISTS = ["my gym playlist", "lofi", "focus", "old bollywood", "party", "sleep"]

MESSAGE_BODIES = [
    "I'm on my way",
    "running ten minutes late",
    "call me back when you get this",
    "meeting moved to 4",
    "did you eat",
    "I'll be there in five",
    "please bring the documents",
    "happy birthday",
    "got the job",
    "sending the money tonight",
]

ROUTINE_NAMES = ["morning", "night", "workout", "commute", "focus", "bedtime", "wind down", "gym"]
ROUTINE_STEPS = [
    "open Spotify and play my gym playlist",
    "turn the flashlight on",
    "read my notifications",
    "set brightness to thirty percent",
    "open maps",
]

CHANNEL_WORDS = {
    "sms": ["by sms", "over sms", "as a text message", "through sms"],
    "whatsapp": ["on whatsapp", "over whatsapp", "through whatsapp", "on whatsaap"],
    "telegram": ["on telegram", "over telegram", "through telegram"],
    "rcs": ["as an rcs message"],
}

NUMBER_LABELS = {
    "mobile": ["on his mobile", "on her mobile", "on mobile"],
    "home": ["on the home number", "on home"],
    "work": ["on his work number", "on work"],
}

# Filler that carries no entity signal. Mirrors TextNormalizer.STOP_WORDS so a
# generated utterance looks like something the real pipeline will actually see.
OPENERS = ["", "", "", "hey ", "okay ", "please ", "can you ", "could you ", "quickly "]
CLOSERS = ["", "", "", " thanks", " please", " ok", " now", " for me"]

# Utterances the user is refusing or negating. The correct answer is *not* to
# flip the polarity and call the tool.
NEGATIONS = [
    "don't turn on wifi",
    "do not switch off bluetooth",
    "never play any song",
    "don't call anybody",
    "stop sending messages to my wife",
    "don't open any app",
    "no need to set an alarm",
    "i don't want to hear notifications",
    "don't mute the music",
    "leave my brightness alone",
    "don't ring my boss",
    "stop playing music",
    "i don't want an alarm tomorrow",
    "don't text Rohan",
    "please don't start the timer",
    "no messages to my mother",
    "don't turn up the volume",
    "leave my contacts alone",
    "forget the timer",
    "don't run any routine",
    "keep the flashlight off",
    "don't open spotify",
    "don't send anything to anybody",
    "stop reading my notifications",
    "i don't want to be called",
    "don't toggle airplane mode",
    "cancel the alarm",
    "don't tell my wife anything",
    "don't change any settings",
    "no music right now",
]

OUT_OF_DOMAIN = [
    "what's the weather tomorrow",
    "book me a flight to goa",
    "how do i bake a cake",
    "what's the capital of france",
    "translate this to hindi",
    "order me a pizza",
    "who won the match yesterday",
    "remind me to take my tablets",
    "calculate two plus two",
    "write me a poem about rain",
    "is it going to rain",
    "stock market kya chal raha hai",
    "delete all my photos",
    "increase my bank balance",
]

AMBIGUOUS_PERSON = [
    "call him",
    "text her",
    "ring my contact",
    "message that person",
    "call them",
    "ask him about it",
]

# The same first name with two different people in the address book is the
# canonical ambiguous case: the resolver must top-2 and ask, and the model must
# not invent which Rahul was meant.
AMBIGUOUS_SETUP = [
    "call Rahul",
    "text Rahul",
    "call Rohit",
    "message Rahul about the meeting",
]

MISSING_SLOT = {
    "set_alarm": ["set an alarm", "wake me up tomorrow", "set my morning alarm", "alarm please"],
    "set_timer": ["set a timer", "start a timer", "timer for"],
    "call_contact": ["call", "phone", "ring"],
    "send_message": ["send a message", "text", "whatsapp"],
    "play_music": ["play", "put on some music", "play something"],
    "open_app": ["open", "launch"],
    "toggle_setting": ["toggle the setting", "switch on that setting"],
}

OUTSIDE_ENUM = {
    "set_brightness": [
        ("set brightness to 150", "percent"),
        ("set brightness to 500", "percent"),
    ],
    "toggle_setting": [
        ("turn on aeroplane mode", "setting"),
        ("toggle the teleporter", "setting"),
    ],
    "set_volume": [
        ("set volume to 200", "percent"),
    ],
}


def _pick(rng: random.Random, options: Any) -> Any:
    """Chooses one option.

    Accepts a scalar or a sequence. A scalar is returned unchanged; it is never
    indexed, because indexing a string yields a character and a single stray
    character in a training slot is invisible to schema validation but poisons
    the data.
    """
    if isinstance(options, (str, bytes)):
        return options
    if isinstance(options, (list, tuple)):
        return options[rng.randrange(len(options))]
    raise TypeError(f"_pick expects a scalar or sequence, got {type(options).__name__}")


def _decorate(rng: random.Random, core: str, *, allow_openers: bool = True) -> str:
    """Adds conversational filler around a core utterance.

    `allow_openers=False` is used for negations and out-of-domain prompts: prefixing
    "can you " to "don't call anybody" produces "can you don't call anybody", which
    no speaker would utter and which teaches the model that the input distribution is
    stranger than it really is.
    """
    openers = OPENERS if allow_openers else [""]
    return f"{_pick(rng, openers)}{core}{_pick(rng, CLOSERS)}"


# ---------------------------------------------------------------------------
# Generators. Each returns (utterance, tool, arguments, tags, notes) or None.
# ---------------------------------------------------------------------------

SlotSpec = tuple[str, list[str]]


def gen_send_message(rng: random.Random, catalog: ToolCatalog):
    ref: SlotSpec = ("relation", RELATIONS) if rng.random() < 0.35 else ("name", NAMES)
    kind, ref_list = ref
    person = _pick(rng, ref_list)
    body = _pick(rng, MESSAGE_BODIES)
    channel = "auto"
    verb = _pick(rng, ["send a message to", "text", "message", "whatsapp", "send a text to", "ping"])
    core = f"{verb} {person} saying {body}"
    if rng.random() < 0.25:
        channel = _pick(rng, list(CHANNEL_WORDS))
        core = f"{verb} {person} on {channel} saying {body}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "send_message",
        "arguments": {"contact_ref": person, "body": body, "channel": channel},
        "tags": [TAG_LLM],
        "notes": f"contact_ref is a raw spoken {kind}",
    }


def gen_call_contact(rng: random.Random, catalog: ToolCatalog):
    ref: SlotSpec = ("relation", RELATIONS) if rng.random() < 0.4 else ("name", NAMES)
    kind, ref_list = ref
    person = _pick(rng, ref_list)
    verb = _pick(rng, ["call", "phone", "ring", "give a call to"])
    core = f"{verb} {person}"
    arguments: dict[str, Any] = {"contact_ref": person}

    if rng.random() < 0.3:
        label = _pick(rng, list(NUMBER_LABELS))
        core = f"{verb} {person} {_pick(rng, NUMBER_LABELS[label])}"
        arguments["number_label"] = label
    if rng.random() < 0.1:
        arguments["sim"] = _pick(rng, [1, 2])
        core += f" on sim {arguments['sim']}"

    return {
        "utterance": _decorate(rng, core),
        "tool": "call_contact",
        "arguments": arguments,
        "tags": [TAG_LLM],
        "notes": f"contact_ref is a raw spoken {kind}; never a number",
    }


def gen_play_music(rng: random.Random, catalog: ToolCatalog):
    kind = _pick(rng, ["any", "any", "track", "artist", "album", "playlist", "liked"])
    if kind == "track":
        query = _pick(rng, TRACKS)
        core = _pick(rng, [f"play {query}", f"put on {query}", f"play the song {query}"])
    elif kind == "artist":
        query = _pick(rng, ARTISTS)
        core = _pick(rng, [f"play {query}", f"play some {query}", f"put on music by {query}"])
    elif kind == "album":
        query = _pick(rng, ALBUMS)
        core = _pick(rng, [f"play the album {query}", f"play {query}"])
    elif kind == "playlist":
        query = _pick(rng, PLAYLISTS)
        core = _pick(rng, [f"play {query}", f"start {query}", f"put on {query}"])
    else:
        # "liked songs" and "resume" must not share a label: the phrasing decides
        # the kind. Labelling "play my liked songs" as kind=any in one branch and
        # kind=liked in another teaches the model that the slot is noise.
        query = ""
        core = _pick(rng, ["resume", "play my liked songs", "start my liked songs"])
        if "liked" in core:
            kind = "liked"
        else:
            kind = "any"

    arguments: dict[str, Any] = {"query": query, "kind": kind}
    if rng.random() < 0.3:
        shuffle = rng.random() < 0.5
        arguments["shuffle"] = shuffle
        core += " on shuffle" if shuffle else " without shuffle"

    return {
        "utterance": _decorate(rng, core),
        "tool": "play_music",
        "arguments": arguments,
        "tags": [TAG_LLM],
        "notes": "empty query means resume/liked songs",
    }


def gen_read_notifications(rng: random.Random, catalog: ToolCatalog):
    app = _pick(rng, ["any", "any", "whatsapp", "telegram", "sms"])
    core = "read my notifications" if app == "any" else f"read my {app} notifications"
    arguments: dict[str, Any] = {"app": app}
    if rng.random() < 0.4:
        who = _pick(rng, NAMES)
        core += f" from {who}"
        arguments["from_ref"] = who
    if rng.random() < 0.3:
        limit = rng.randint(1, 10)
        core += f" the latest {limit}"
        arguments["limit"] = limit
    return {
        "utterance": _decorate(rng, core),
        "tool": "read_notifications",
        "arguments": arguments,
        "tags": [TAG_LLM],
        "tags_note": None,
    }


def gen_reply_to_last(rng: random.Random, catalog: ToolCatalog):
    body = _pick(rng, MESSAGE_BODIES)
    app = _pick(rng, ["any", "any", "whatsapp", "telegram", "sms"])
    core = f"reply saying {body}"
    if app != "any":
        core = f"reply on {app} saying {body}"
    arguments: dict[str, Any] = {"body": body, "app": app}
    if rng.random() < 0.3:
        who = _pick(rng, NAMES)
        core += f" to {who}"
        arguments["from_ref"] = who
    return {
        "utterance": _decorate(rng, core),
        "tool": "reply_to_last",
        "arguments": arguments,
        "tags": [TAG_LLM],
    }


def gen_media_control(rng: random.Random, catalog: ToolCatalog):
    action = _pick(rng, ["pause", "resume", "next", "previous", "stop", "like_current"])
    core = {
        "pause": ["pause the music", "pause it"],
        "resume": ["resume the song", "keep playing"],
        "next": ["skip this song", "next track"],
        "previous": ["play the previous song", "go back one"],
        "stop": ["stop the music"],
        "like_current": ["like this song"],
    }[action][0]
    return {
        "utterance": _decorate(rng, core),
        "tool": "media_control",
        "arguments": {"action": action},
        "tags": [TAG_FASTPATH],
        "notes": "already deterministic; the model must not normally see this",
    }


def gen_teach_alias(rng: random.Random, catalog: ToolCatalog):
    alias = _pick(rng, ["wife", "bunty", "boss", "mummy", "bhaiya", "dada", "chief"])
    person = _pick(rng, NAMES)
    kind = "relation" if alias in ("wife", "mummy", "dada", "boss") else "nickname"
    core = _pick(rng, [
        f"remember {alias} means {person}",
        f"call {person} {alias} from now on",
        f"{alias} is {person}",
        f"save {person} as {alias}",
    ])
    return {
        "utterance": _decorate(rng, core),
        "tool": "teach_alias",
        "arguments": {"alias": alias, "contact_ref": person, "kind": kind},
        "tags": [TAG_LLM],
    }


def gen_run_routine(rng: random.Random, catalog: ToolCatalog):
    name = _pick(rng, ROUTINE_NAMES)
    core = _pick(rng, [f"run my {name} routine", f"start the {name} routine", f"do my {name} routine"])
    return {
        "utterance": _decorate(rng, core),
        "tool": "run_routine",
        "arguments": {"name": name},
        "tags": [TAG_LLM],
        "notes": f"steps: {len(ROUTINE_STEPS)}",
    }


def gen_ask_clarification(rng: random.Random, catalog: ToolCatalog):
    tool_name = _pick(rng, list(MISSING_SLOT))
    utterance = _decorate(rng, _pick(rng, MISSING_SLOT[tool_name]))
    question = _pick(rng, {
        "set_alarm": "What time should I set the alarm for?",
        "set_timer": "How long should the timer run?",
        "call_contact": "Who should I call?",
        "send_message": "Who should I message, and what should it say?",
        "play_music": "What should I play?",
        "open_app": "Which app should I open?",
        "toggle_setting": "Which setting did you mean?",
    }[tool_name])
    return {
        "utterance": utterance,
        "tool": "ask_clarification",
        "arguments": {"question": question},
        "tags": [TAG_LLM, TAG_MISSING_SLOT],
        "expect_clarification": True,
        "notes": f"would have been {tool_name}, but a required slot is absent",
    }


def gen_outside_enum(rng: random.Random, catalog: ToolCatalog):
    tool_name = _pick(rng, list(OUTSIDE_ENUM))
    utterance, _slot = _pick(rng, OUTSIDE_ENUM[tool_name])
    question = _pick(rng, {
        "set_brightness": "Brightness has to be between 0 and 100. What did you mean?",
        "toggle_setting": "I don't know that setting. Which one did you mean?",
        "set_volume": "Volume has to be between 0 and 100. What did you mean?",
    }[tool_name])
    return {
        "utterance": _decorate(rng, utterance),
        "tool": "ask_clarification",
        "arguments": {"question": question},
        "tags": [TAG_LLM, TAG_OUTSIDE_ENUM],
        "expect_clarification": True,
        "notes": f"would have been {tool_name} with an out-of-range or unknown value",
    }


def gen_ambiguous(rng: random.Random, catalog: ToolCatalog):
    utterance = _decorate(rng, _pick(rng, AMBIGUOUS_PERSON))
    question = _pick(rng, [
        "I have more than one contact matching that. Which one?",
        "That matches two people. Which did you mean?",
        "Which contact did you mean?",
    ])
    return {
        "utterance": utterance,
        "tool": "ask_clarification",
        "arguments": {"question": question},
        "tags": [TAG_LLM, TAG_AMBIGUOUS],
        "expect_clarification": True,
        "notes": "pronoun with no antecedent; the model cannot pick the person",
    }


def gen_out_of_domain(rng: random.Random, catalog: ToolCatalog):
    utterance = _decorate(rng, _pick(rng, OUT_OF_DOMAIN), allow_openers=False)
    reason = _pick(rng, [
        "I don't have a tool for that.",
        "That's outside what I can do.",
        "I can't do that one.",
    ])
    return {
        "utterance": utterance,
        "tool": "unsupported",
        "arguments": {"reason": reason},
        "tags": [TAG_LLM, TAG_OUT_OF_DOMAIN],
        "notes": "the correct behaviour is to admit ignorance, not to improvise",
    }


def gen_negation(rng: random.Random, catalog: ToolCatalog):
    utterance = _decorate(rng, _pick(rng, NEGATIONS), allow_openers=False)
    reason = _pick(rng, [
        "That is a request not to do something; I don't have a tool to carry it out.",
        "I can't act on a negative instruction.",
    ])
    return {
        "utterance": utterance,
        "tool": "unsupported",
        "arguments": {"reason": reason},
        "tags": [TAG_LLM, TAG_OUT_OF_DOMAIN, TAG_NEGATION],
        "notes": "polarity must not be flipped into a tool call",
    }


def gen_set_flashlight(rng: random.Random, catalog: ToolCatalog):
    state = _pick(rng, ["on", "off", "toggle"])
    core = {
        "on": ["turn on the flashlight", "switch the torch on"],
        "off": ["turn off the flashlight"],
        "toggle": ["toggle the flashlight"],
    }[state][0]
    return {
        "utterance": _decorate(rng, core),
        "tool": "set_flashlight",
        "arguments": {"state": state},
        "tags": [TAG_FASTPATH],
        "notes": "deterministic",
    }


def gen_toggle_setting(rng: random.Random, catalog: ToolCatalog):
    setting = _pick(rng, ["wifi", "mobile_data", "bluetooth", "airplane_mode", "hotspot", "do_not_disturb"])
    state = _pick(rng, ["on", "off", "toggle"])
    spoken = {"mobile_data": "mobile data", "airplane_mode": "airplane mode",
              "do_not_disturb": "do not disturb"}.get(setting, setting)
    core = f"turn {state if state != 'toggle' else ''} {spoken}".replace("  ", " ").strip()
    if state == "toggle":
        core = f"toggle {spoken}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "toggle_setting",
        "arguments": {"setting": setting, "state": state},
        "tags": [TAG_FASTPATH],
        "notes": "deterministic",
    }


def gen_set_brightness(rng: random.Random, catalog: ToolCatalog):
    action = _pick(rng, ["set", "up", "down", "auto"])
    arguments: dict[str, Any] = {"action": action}
    if action == "set":
        pct = _pick(rng, [0, 5, 10, 25, 30, 40, 50, 60, 75, 80, 100])
        arguments["percent"] = pct
        core = f"set brightness to {pct} percent"
    elif action == "auto":
        core = "set brightness to auto"
    else:
        core = f"make it {'brighter' if action == 'up' else 'less bright'}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "set_brightness",
        "arguments": arguments,
        "tags": [TAG_FASTPATH],
        "notes": "deterministic",
    }


def gen_set_volume(rng: random.Random, catalog: ToolCatalog):
    action = _pick(rng, ["set", "up", "down", "mute", "unmute"])
    arguments: dict[str, Any] = {"action": action}
    core = {
        "mute": "mute the volume", "unmute": "unmute",
        "up": "turn the volume up", "down": "turn the volume down",
    }.get(action, "")
    if action == "set":
        pct = _pick(rng, [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100])
        arguments["percent"] = pct
        stream = "media"
        if rng.random() < 0.3:
            stream = _pick(rng, ["media", "ring", "alarm", "notification"])
            arguments["stream"] = stream
        core = f"set volume to {pct} percent"
        if stream != "media":
            core += f" for {stream}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "set_volume",
        "arguments": arguments,
        "tags": [TAG_FASTPATH],
        "notes": "deterministic",
    }


def gen_set_alarm(rng: random.Random, catalog: ToolCatalog):
    hh, mm = _pick(rng, [(6, 30), (7, 0), (8, 15), (9, 0), (5, 45), (21, 30)])
    time = f"{hh:02d}:{mm:02d}"
    arguments: dict[str, Any] = {"time": time}
    core = _pick(rng, [f"set an alarm for {time}", f"wake me up at {time}"])
    if rng.random() < 0.4:
        repeat = _pick(rng, ["once", "daily", "weekdays", "weekends"])
        arguments["repeat"] = repeat
        core += "" if repeat == "once" else f" {repeat}"
    if rng.random() < 0.3:
        label = _pick(rng, ["gym", "meeting", "wake up", "medicine", "class"])
        arguments["label"] = label
        core += f" called {label}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "set_alarm",
        "arguments": arguments,
        "tags": [TAG_FASTPATH],
        "notes": "deterministic when time parses",
    }


def gen_set_timer(rng: random.Random, catalog: ToolCatalog):
    unit = _pick(rng, ["minutes", "seconds", "hours"])
    amount = _pick(rng, [5, 10, 15, 20, 30, 45, 60, 90, 120])
    seconds = amount * {"seconds": 1, "minutes": 60, "hours": 3600}[unit]
    core = f"set a timer for {amount} {unit}"
    arguments: dict[str, Any] = {"seconds": seconds}
    if rng.random() < 0.25:
        label = _pick(rng, ["pasta", "tea", "workout", "laundry"])
        arguments["label"] = label
        core += f" for the {label}"
    return {
        "utterance": _decorate(rng, core),
        "tool": "set_timer",
        "arguments": arguments,
        "tags": [TAG_FASTPATH],
        "notes": "deterministic when duration parses",
    }


def gen_open_app(rng: random.Random, catalog: ToolCatalog):
    app = _pick(rng, APPS)
    core = _pick(rng, [f"open {app}", f"launch {app}", f"start {app}"])
    return {
        "utterance": _decorate(rng, core),
        "tool": "open_app",
        "arguments": {"app_name": app},
        "tags": [TAG_FASTPATH],
        "notes": "app_name stays a raw spoken string, not a package name",
    }


# Only residual tools are in the default set.
LLM_GENERATORS: list[Callable[[random.Random, ToolCatalog], dict[str, Any]]] = [
    gen_send_message,
    gen_call_contact,
    gen_play_music,
    gen_read_notifications,
    gen_reply_to_last,
    gen_teach_alias,
    gen_run_routine,
    gen_media_control,  # fast but sometimes ambiguous phrasing
    gen_ask_clarification,
    gen_outside_enum,
    gen_ambiguous,
    gen_out_of_domain,
    gen_negation,
]

FAST_GENERATORS: list[Callable[[random.Random, ToolCatalog], dict[str, Any]]] = [
    gen_set_flashlight,
    gen_toggle_setting,
    gen_set_brightness,
    gen_set_volume,
    gen_set_alarm,
    gen_set_timer,
    gen_open_app,
]


def _label_conflict(
    previous: tuple[str, str] | None, tool: str, arguments: dict[str, Any]
) -> str | None:
    """Returns a description of the conflict, or None if the labels agree.

    Two records for one utterance conflict when they name different tools, or
    when a structured slot differs. Prose slots are excluded: a rephrased
    clarification question for the same utterance is variety, not contradiction.
    """
    if previous is None:
        return None
    prev_tool, prev_args = previous
    if prev_tool != tool:
        return f"{prev_tool}{prev_args} vs {tool}{json.dumps(arguments, sort_keys=True)}"
    prev_parsed = json.loads(prev_args)
    for key, value in arguments.items():
        if key in PROSE_SLOTS:
            continue
        if key in prev_parsed and prev_parsed[key] != value:
            return (
                f"same tool {tool} but slot {key!r} is "
                f"{prev_parsed[key]!r} in one record and {value!r} in another"
            )
    return None


def make_records(
    catalog: ToolCatalog,
    generators: list[Callable],
    per_tool: int,
    seed: int,
    split: str,
    prefix: str,
    exclude: set[str] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Generates validated, de-duplicated records for each generator.

    Two guarantees matter here, and both exist because the obvious implementation
    gets them wrong when the template banks are small:

    * **No duplicate utterances.** With only a few dozen phrases per tool,
      sampling 60 examples silently produces the same sentence seven times. Those
      rows are wasted capacity, and they inflate the training distribution towards
      whichever phrasing the bank happens to favour.

    * **No contradictory labels.** If one utterance is produced twice with
      *different* expected tool calls, the set is unusable: the model cannot be
      right for both. This is raised as an error rather than filtered, because it
      always means the template banks disagree with each other.

    `exclude` removes utterances already used by another split, which keeps the
    eval set honest. When the banks cannot supply enough distinct phrasings the
    shortfall is returned and reported rather than padded with duplicates.
    """
    rng = random.Random(seed)
    exclude = {u.strip().lower() for u in (exclude or set())}

    seen: set[str] = set()
    label_of: dict[str, tuple[str, str]] = {}
    records: list[dict[str, Any]] = []
    shortfall: dict[str, int] = {}
    counter = 0

    for gen in generators:
        accepted = 0
        # Generators are template-based, so the number of distinct phrasings a
        # bank can supply is finite. Retry generously, then report what is
        # actually available instead of looping forever.
        max_attempts = max(per_tool * 80, 400)
        for _ in range(max_attempts):
            if accepted >= per_tool:
                break
            spec = gen(rng, catalog)
            utterance = spec["utterance"].strip()
            key = utterance.lower()

            # The contradiction check must come before the duplicate skip.
            # Checked afterwards it is dead code: a repeated utterance is already
            # `seen` by the time we would compare its labels, so the two branches
            # that matter most are exactly the two the guard never sees.
            contradiction = _label_conflict(label_of.get(key), spec["tool"], spec["arguments"])
            if contradiction:
                raise ValidationError(
                    f"contradictory label for utterance {utterance!r}: {contradiction}"
                )
            argument_key = json.dumps(
                {k: v for k, v in spec["arguments"].items() if k not in PROSE_SLOTS},
                sort_keys=True,
            )

            if key in exclude or key in seen:
                continue

            example = make_example(
                catalog,
                utterance,
                spec["tool"],
                spec["arguments"],
                tags=[t for t in spec.get("tags", []) if t],
                split=split,
                expect_clarification=spec.get("expect_clarification", False),
                notes=spec.get("notes"),
            )
            counter += 1
            example["id"] = f"{prefix}-{counter:06d}"
            records.append(example)
            seen.add(key)
            label_of[key] = (spec["tool"], argument_key)
            accepted += 1

        if accepted < per_tool:
            shortfall[gen.__name__] = per_tool - accepted

    return records, shortfall


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate synthetic function-calling training data.")
    parser.add_argument("--out", default="data", help="output directory (default: data)")
    parser.add_argument("--tools", default=None, help="path to tools.json")
    parser.add_argument("--per-tool", type=int, default=60, help="examples per generator (default: 60)")
    parser.add_argument("--seed", type=int, default=17, help="deterministic seed")
    parser.add_argument("--include-fastpath", action="store_true", help="also emit fast-path examples")
    parser.add_argument("--eval-per-tool", type=int, default=8, help="held-out examples per generator")
    args = parser.parse_args()

    catalog = load_catalog(args.tools) if args.tools else load_catalog()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Train first, then hand its utterances to the eval split so the two are
    # disjoint. A contaminated eval set reports a flattering number that does not
    # survive contact with real users.
    train, train_short = make_records(
        catalog, LLM_GENERATORS, args.per_tool, args.seed, "train", "train"
    )
    train_utterances = {r["utterance"] for r in train}
    dev, dev_short = make_records(
        catalog, LLM_GENERATORS, args.eval_per_tool, args.seed + 1000, "dev", "dev",
        exclude=train_utterances,
    )
    eval_records, eval_short = make_records(
        catalog, LLM_GENERATORS, args.eval_per_tool, args.seed + 2000, "eval", "eval",
        exclude=train_utterances | {r["utterance"] for r in dev},
    )

    write_jsonl(out_dir / "train_llm.jsonl", assign_ids(train, "train"))
    write_jsonl(out_dir / "dev_llm.jsonl", assign_ids(dev, "dev"))
    write_jsonl(out_dir / "eval_llm.jsonl", assign_ids(eval_records, "eval"))

    print(f"catalog version {catalog.version}, {len(catalog.tools)} tools")
    print(
        f"wrote {len(train)} train / {len(dev)} dev / {len(eval_records)} eval "
        f"examples for the fallback LLM to {out_dir}"
    )

    if args.include_fastpath:
        fast, fast_short = make_records(
            catalog, FAST_GENERATORS, args.per_tool // 2, args.seed, "train", "fast"
        )
        write_jsonl(out_dir / "train_fastpath.jsonl", assign_ids(fast, "fast"))
        print(f"wrote {len(fast)} fast-path examples (tagged 'fast')")

    for label, short in (("train", train_short), ("dev", dev_short), ("eval", eval_short)):
        if short:
            detail = ", ".join(f"{name} -{n}" for name, n in sorted(short.items()))
            print(
                f"NOTE: {label} split is short of the request ({detail}). "
                "The template banks cannot supply that many distinct phrasings; "
                "add templates rather than allowing duplicates.",
                file=sys.stderr,
            )

    counts: dict[str, int] = {}
    for record in train:
        for tag in record.get("tags", []):
            counts[tag] = counts.get(tag, 0) + 1
    if counts:
        print("tag distribution (train):", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as exc:
        print(f"schema validation failed: {exc}", file=sys.stderr)
        raise SystemExit(2)