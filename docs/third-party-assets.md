# Third-party assets

| File | Source | Licence |
| --- | --- | --- |

## First-party drawables

`app/src/main/res/drawable/ic_field_*.xml` are not third-party. They are plain
stroked vector paths written for this project, because the app depends on
`material-icons-core` only — about forty glyphs, none of them a globe, a
mountain, a speedometer, a clock, a burst or a key — and adding
`material-icons-extended` would ship several thousand vectors to use six.

`ic_action_pause.xml` and `ic_action_more.xml` are first-party on the same
terms, and filled rather than stroked: they mark actions in the app bar,
alongside the filled core icons, where the `ic_field_*` set marks data inside a
card. `material-icons-core` has `PlayArrow` but no `Pause`, and showing
`PlayArrow` while running would claim the opposite of what tapping it does.
`ic_action_more` is three dots — drawable in four lines — so that no app-bar
action rests on a guess about which forty glyphs that artifact happens to ship.
