---
name: explain
description: Explain how or why something in the simulation behaves as it does — a mechanic, a population pattern, a creature's behaviour, a number in the viewer. Use whenever the user asks "how does X work", "why are/does…", "what makes…", "how are they surviving", "where does X come from", or otherwise wants system behaviour understood rather than changed. Answers are a sentence and a few lines, with a graphic only when it helps, a "why" followed down to its root cause, fast to produce and read, written in ASD-STE100 Simplified Technical English, with no fix suggestions. Not for requests to change, tune or fix anything.
---

# Explaining system behaviour

The user is building a mental model of the world one question at a time.
Each answer is one step in a conversation, not a report: follow-up questions
will come, so a fast, small, correct answer beats a complete one. Aim for
something readable in under a minute on a phone.

## The shape of every answer

1. **One sentence that answers the question.** The mechanism in plain words,
   first. For a "why", that is the root cause (see below), not the first
   link in the chain.
2. **A simple graphic, when it says it better than words.** A causal chain,
   a loop, a before/after or a sketch of the levels often does; a single
   fact or one number does not, and then the graphic is left out. When there
   is one: plain text in a fenced code block, so it renders the same in the
   terminal and the app; at most about 12 lines and 56 characters wide,
   because the reader is often on a phone; the one or two numbers that
   matter on the arrows.
3. **At most three bullets** of supporting facts. Each carries its number
   where it has one, and each number comes from a constant just read in the
   code or a measurement just taken.
4. **Stop.** No fix suggestions, no options, no "what I'd change", no next
   steps, no offer to do more. If the explanation reveals that something
   looks wrong, saying so in one plain sentence is enough; what to do about
   it is a separate question the user will ask if they want it.

About 150 words, graphic included. If it is running longer, the answer is
trying to cover the follow-up questions; cut them.

## Write it in ASD-STE100

Every answer is written in ASD-STE100 Simplified Technical English: the
sentence, the bullets and the labels in the graphic. The reader is often on a
phone and reads quickly, and STE removes the second reading of a sentence.
The rules that matter most here:

- **One topic in each sentence.** A sentence that describes is at most 25
  words; an instruction is at most 20. A paragraph is at most six sentences.
- **Active voice, simple tenses.** Present, simple past or simple future.
  "The hunter eats the body", not "the body is eaten" or "has been eaten".
- **Approved words, with their approved meaning.** Use the STE dictionary
  word where one fits ("start", not "commence"; "increase", not "boost";
  "show", not "reveal"). One word has one meaning in an answer.
- **The project's names are technical names.** Herbivore, glycogen,
  fertility, steward, clade, tile and the other names that the code and the
  viewer use are permitted as they are. Do not replace them with near words.
- **No -ing forms** as nouns or adjectives, except in a technical name.
  "When the herd grazes", not "while grazing".
- **No phrasal verbs, no idioms, no contractions.** "Remove", not "take
  out"; "does not", not "doesn't".
- **Keep the articles.** "The tile gets 0.044", not "tile gets 0.044".
- **Each bullet is a full sentence**, or a short list of nouns that
  completes the line before it.

Numbers, units, file names and code identifiers stay exactly as they are.
STE controls the words; it does not change the facts or the shape of the
answer above.

## Finding the root cause

A "why" is answered at its root, not at the first cause that comes to hand.
Use the five-whys habit: take the first answer and ask why of it, then of
that, until the chain reaches something the code states (a rule, a constant,
a gate) or a measurement shows, and asking again would only be asking why
the code is written that way. That is usually three to five steps, sometimes
one; stop as soon as the next why has no answer in the code or the data.

It is a way of thinking, not a format. Never print "Why 1 / Why 2"; lead
with the root cause, and let the chain show only as much as the reader needs
to believe it, in a sentence or as the arrows of the graphic. Each link has
to be read in the code or measured, not reasoned to; a link that is inferred
says so in the words that carry it.

Use it when the question asks why something happens, or when the honest
answer to a "how" is itself a symptom. A plain "how does X work" or "how
long does Y take" needs no chain: answer it.

For example, "why does a hunter only ever raise one cub?" One cub is what
the founder's fat pays for, but that is the first link: fat is never laid
down again, because fat is only banked from surplus, and a hunter's kills
bring in less than its resting burn. The answer leads with that deficit.

### Example

Question: "How does the dung mechanism work?"

> Each meal puts its undigested part on the tile below the animal. This
> increases the fertility of the tile, and the fertility sets how much grass
> the tile can hold.
>
> ```
>  grass ──eaten──▶ herbivore
>    ▲                  │ 80% of plant energy
>    │ cap = fertility  │ passes through
>    │       × moisture ▼
>  fertility ◀─ +0.044 per unit eaten ── dung
>  (capped at 1.0, never falls)
> ```
>
> - A dead body adds 0.10 fertility for each unit of mass that is not eaten.
>   Each adjacent tile gets 40% of that value.
> - No process decreases fertility. The growth of grass does not use it.

## Producing it quickly

- **Answer from the code first.** Find the constant or method with a grep and
  read it; that is usually the whole answer. Name a file only if the reader
  would need to go there.
- **At most one cheap measurement**, and only when the question is about the
  live world — a few API calls, not a simulation run. If a number would need
  a long run, give the model's arithmetic and say that is what it is.
- **A handful of tool calls**, not dozens. A question that needs a real
  investigation is better answered small now, with the open part named in a
  sentence, than late.
- **Change nothing.** This skill reads; it does not edit, commit or ship.
- **A root cause can need a measurement** the shallow answer did not. Take
  the one cheap measurement for the link that matters most, and say which
  links are the model's arithmetic instead.

## When the question is unclear

If two readings of the question would lead to different answers, ask one
short clarifying question instead of guessing, and offer the likely readings
as the choices. "The 2nd and 3rd level" is the classic case: the floors run
-2 to +3 around the ground, and "2nd" could be +2, or the second floor from
the top or bottom. If one reading is clearly most likely, state it in a few
words and answer it rather than asking.

## Getting it right

- **Never compare two numbers that count different things.** A tile count
  "fresh vs live" once compared grass tiles with a cap above zero against
  every grass-type tile, and the conclusion was wrong. Check both sides of a
  comparison were measured the same way before drawing the arrow between them.
- **If an earlier answer turns out wrong, say so first**, in one line, before
  the new answer.
- **Numbers from now, not from memory.** A count remembered from earlier in
  the session or from another session is stale; the world moves.

## Where the live answers are

The live world is https://world.evarium.cc. Read-only endpoints:

| endpoint | gives |
|---|---|
| `/api/health` | tick, entity count, tick time against its budget, commit |
| `/api/world` | seed, levels, size |
| `/api/population` | role counts over time and the steward's floors and ceilings |
| `/api/world/entity/{id}` | one creature: role, books, action, climate, genome |
| `/api/world/tile/{z}/{x}/{y}` | one tile: type, fertility, food and its cap |
| `/api/world/vegetation/{z}` | raw grass per tile, 0..100; 255 means the tile never grows |
| `/api/world/water/{z}` | per tile: 1 open water, 2 shallows, 3 tannin pool, 4 mire |
| `/api/world/climate/{field}/{z}` | light, temperature or humidity, quantised to 0..255 |
| `wss://…/api/world/stream` | entities; send `{"cmd":"level","z":N,"below":false}` per level |

Levels are indexed z = 0 upwards; the hello message names the ground's index
(2 on seed 7). Talk about floors as the viewer does: the ground is 0, the ones
above are +1, +2, +3 and the caves are -1, -2. A fresh world of the live seed
for comparison is `Worlds.demo(seed)` from a small main against
`engine/build/classes/java/main`.
