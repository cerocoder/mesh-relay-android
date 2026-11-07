# Recipes

Short, practical jobs. Each one combines features from earlier pages. The numbers are examples
only; your own will differ.

Throughout, one rule makes the measurements trustworthy: **change one thing at a time, tap Reset,
wait long enough, then compare the same relay. If you want to keep a run, export it before you tap Reset.**

## 1. Find the best place for an antenna

1. Put the node where you want to test. Connect and open **Relays**. Tap **Reset**.
2. Wait until each main relay has carried at least 10–15 packets. Write down the top relays, their average SNR and their packet counts.
3. Move to the next candidate place. Tap **Reset**. Wait for a similar number of packets. Write down the same
   numbers.
4. Prefer the place where several good relays appear, not only the highest single number.

*Why it works:* Reset keeps the old place's packets out of the new average. The same relay, at the
same time of day, is the fair comparison.

## 2. Check whether a higher or outdoor position is worth the effort

Raise the antenna in steps (windowsill, outside the window, on a pole). Repeat Recipe 1 at each
height. A gain of several dB in average SNR is worth keeping. A change within a dB or so may be
just noise: repeat the test before deciding.

## 3. Compare two antennas

Use the same node, same position and same orientation. Test antenna A until the main relays have carried at least 10–15 packets and write down
the averages (or tap **Export data** to keep the run). Then tap **Reset**, swap to antenna B and wait
for a similar number of packets. Compare the averages of the relays that appear in both runs. Reset clears the history, so
export *before* it.

Tip: a drop on *all* relays at once points at the antenna or its cable or connector, not at the
environment.

## 4. Aim a directional antenna

1. Open the **Graph** of the relay you want to reach.
2. Turn the antenna a few degrees. Wait for at least 10–15 new packets from that relay.
3. Watch whether the SNR dots move right. Bookmark the heading with the best results, and add
   a note.

The candidate's position on its node card tells you roughly which direction to aim.

## 5. Take a walk and map the good spots

Open the Graph, then walk with the node and phone. Afterwards, scroll the history and add a
bookmark at each good moment. With **Use phone location** on, each bookmark remembers where you
stood, and the 🌐 button opens it on a map.

## 6. Read a place you have just arrived at

At a campsite, a hostel or a new town, open **Relays** for a few minutes. You see which relays
reach you, their roles, and how far away they are. A relay with a strong signal and role
`ROUTER` is a good anchor; many weak ones mean a poor spot, so try moving a little.

## 7. Check a node you look after

You maintain a rooftop node. Connect to a nearby node and open **Relays**. If your rooftop node
carries traffic with a healthy SNR, it is doing its job. If it never appears as a relay, or its
card shows very low SNR, then check its antenna, cable and power. On its node card, **Uptime** and
**Restarts** show whether it keeps rebooting.

## 8. Hunt for interference

If RSSI is high on every relay but SNR is poor and scattered, suspect local noise. Switch off or
move nearby electronics one at a time (chargers, power supplies, LED lamps, routers). After each
change, tap **Reset** and watch the SNR. Noise sources are often found this way.

## 9. Share evidence when you ask for help

When you describe a problem to someone, export a CSV (before any Reset) and attach it, with the antenna, the place and
the node's radio settings in your own words. "SNR averaged −6 dB on the main relay over twenty
minutes" is much easier to help with than "the range is bad".

## 10. Plan where to add a second node

On the relay cards, look at **Known nodes**. A relay that carries many nodes is important to you.
If it is far or weak, a second node placed to hear it better, or to hear an alternative relay, adds
real resilience. Use **Remote nodes** to see whether the same far nodes also arrive through a
second relay.

Next: [Limits and troubleshooting](10-limits.md).
