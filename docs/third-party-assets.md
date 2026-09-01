# Third-party assets

| File | Source | Licence |
| --- | --- | --- |

## First-party drawables

`app/src/main/res/drawable/ic_field_*.xml` are not third-party. They are plain
stroked vector paths written for this project, because the app depends on
`material-icons-core` only — about forty glyphs, none of them a globe, a
mountain, a speedometer, a clock, a burst or a key — and adding
`material-icons-extended` would ship several thousand vectors to use six.
