# Design

The app fills the MG4's 1920 × 720 screen: now playing on the left, the
library on the right. It's read at a glance, at arm's length, while
driving.

Dial: ENERGY 2 / RHYTHM 1 / MOTION 1. The album-lit panel is the one bold
element; the library side stays calm and regular so it reads at a glance;
motion is limited to presses and the panel's colour easing to a new cover.

## Colour

- One accent, amber, like the car's dashboard lighting: the play button,
  the selected tab, the song playing, the main button on a page.
- Neutrals around it: cool grey by day, blue-graphite by night.
- Three text colours (`ink`, `ink_2`, `ink_3`), each at least 4.5:1 on every
  background in both skins (checked by `textIsReadableInBothSkins`).

## Skins

Day and night follow the car's own setting (SAIC `SKIN_THEME_CONFIG`). The
now-playing panel is dark in both.

## Now-playing panel

The cover fills it. Its background is a deep shade of the cover's own
colour (10 to 17% lightness), with a scrim under the text, so white text
stays readable even on a white cover.

## Type

Barlow throughout: it's drawn from road signs and number plates, so it
suits a car. Its semi-condensed cut for titles, so long album names fit.

## Size and shape

- Nothing to press is smaller than 64dp; most controls are 80 to 116dp.
- Text is 19sp at the smallest.
- Pill buttons for actions, rounded rectangles (10 to 14dp) for covers,
  cards and rows, a circle for play.

## States

Every page has an empty, a loading and an error view, and says what to do
next ("Check the car's internet connection, then try again").
