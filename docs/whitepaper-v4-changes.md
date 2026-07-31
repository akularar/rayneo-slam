# V4 versus V3 — complete change list

Verified by a block-level diff of `whitepaper-v3.html` against `whitepaper-v4.html`, so this
list is exhaustive rather than remembered. Anything not named below is unchanged.

**Totals:** 6,468 → 8,157 words. 20 → 22 pages. 13 → 12 numbered sections. 7 → 8 figures.
26 → 27 references. One table in V3, none in V4. Styling, typography, colours and all seven
original figures untouched.

---

## New material

| Where | What |
|---|---|
| **§5.1** | Three new paragraphs: safe binding to the runtime's initialised library copy and why a private copy crashes; the configuration value that is accepted and silently ignored, and how measurement exposed it; the vendor's published pose binding having the wrong argument list. New callout box, "the rule that came out of this layer". |
| **§5.2** (new subsection) | The pose-convention recovery in full: no stated convention, candidates cycled on device, the convention that passed and was wrong, degenerate test motions, the single reflection that fixed both symptoms, and the generalised lesson about acceptance tests. |
| **Figure 2** (new) | Head rolled sideways, content rolling with it versus content holding level. Placed in §5.2. |
| **§5.3** | Expanded from one paragraph to five: calibration form, the single surface drawn in two viewport-and-scissor passes, how the projection is assembled, the both-halves rule for overlay content, and the missing interpupillary baseline stated as a limitation. |
| **§5.4** (new subsection) | Baked static geometry and one draw call per eye, plus the reflected view matrix and the back-face culling trap that required rewinding geometry and flipping the front-face rule together. |
| **§5.5** (new subsection) | Pose-driven drawing in place of panel-locked drawing, the load measurements, zero allocation on the draw path, hoisted constant work, and the dropped overlay layer. |
| **§5.6** (new subsection) | The glTF reader replacing the engine asset pipeline, the two hardware limits it handles in the open, and the platform-tools-only build. |
| **§10, Discussion** | Two entirely new reflections: the ranking of faults by cost (the crash was the cheapest bug) and where power actually goes (draw less often, not faster). |
| **§11, Future Work** | The "Prototype" entry expanded from one sentence to four concrete next steps, including the controlled comparison against the engine build and the per-eye offset. |
| **§2.2** | One paragraph became five, one per obstacle class, with power named as a design input. |
| **Abstract** | Second paragraph rewritten to cover the engineering account, the well-formed-but-wrong faults, and the load figures for the client. |
| **References** | New entry [27], Khronos glTF 2.0 specification, cited in §5.6. |

## Corrections to statements that were wrong in V3

Both were in the Discussion and both contradicted the repository record.

1. **Tracker status field.** V3 said it "reads the same value whether the tracker is starting
   up or running well, so it told us nothing". The device actually reports 0 for initialising,
   1 for tracking, 2 for failed; our error was reading 0 as success, which made a tracker that
   never started look healthy. V4 states it that way.
2. **Which motion exposed which error.** V3 attributed the roll error to facing away from the
   anchor. Roll was wrong in every orientation, including the default one; facing away is what
   exposed the reversed pitch. V4 separates the two.

## Removed at your request

V3's evaluation material and the whole test-methodology narrative are gone, on the grounds
that the numbers rested on single short captures and that development tooling difficulties do
not belong in front of a reader.

| Where | What |
|---|---|
| **V3 §8, Evaluation** | Section deleted in full, with Table 1 and every stability figure: the 133 s stationary capture, the 0.029 m net travel, the per-window millimetre figures, the 0.21 m self-recovered excursion and the 2.4 m relocalisation jump. |
| **Validation section** | The section written for V4 on testing without screen capture was removed again, together with its callout on what a black screenshot costs. |
| **Discussion** | The paragraph on working with a wearer as the only measuring instrument removed. |
| **Abstract, §2.2, §3** | Clauses about black screen capture removed. |
| **§2.3, §7 callout, §11** | Claims of millimetre-scale stability at rest replaced by the qualitative statement that content holds its place and returns to the same coordinates. |
| **§11, Future Work** | References to Table 1 and to the replicated reference scene rewritten to stand on their own. |

The load figures survive, since they sit inside §5.5 where they justify a design decision:
60 fps to 28.6 fps, graphics unit 2.7 %, application 3 %, tracking service 12 %. The paragraph
giving the artefact size and source line count was removed from §5.5 as well; the Discussion
still cites 497 KB in the engine-trade argument.

## Removed or compressed

| Where | What |
|---|---|
| **§4** | Callout "why persistence outlasts recognition in difficulty" removed. Subsection headings 4.1 and 4.2 removed, their text kept as two tightened paragraphs. Failure-chain paragraph shortened. |
| **§9** | Closing paragraph condensed from five sentences into one. |
| **§11** | Four paragraphs reduced to two; the "error and attention" paragraph merged into the identification paragraph. |
| **§12** | Four V3 paragraphs dropped as duplication, since §5 and §6 now cover the same ground in more detail: the configuration-value story, the status-field story, the crash story, and the screenshot story. |
| **§5.1 callout** | Second paragraph removed. It made the same point about plausible signals that §12 now develops at length with the ranking of faults by cost. |

## Moved

- The **motion-to-photon callout and the stack figure** followed V3's §5.3 on presentation.
  With §5 rebuilt they now sit after §5.5, next to the latency and power material they
  discuss, with §5.6 closing the section on content and build.
- No other section changed position.

## Mechanical

- Sections renumbered 1 to 12 in document order; subsection numbers follow their parent, so
  V3's 9.1 is now 8.1.
- Figures renumbered in document order. V3 Figure 2 through 7 are now 3 through 8, with the
  new frame-convention figure taking number 2. The four inline figure references in the text
  were updated to match.

## Checks run on V4

Banned vocabulary and constructions: none present. Contractions: none. Long dashes: none in
prose, one en dash inside a numeric range where it belongs. References: all 27 cited in the
text. Withheld by intent, unchanged from V3: library and symbol names, dispatch offsets,
configuration-value encodings, and the loader bootstrap chain.
