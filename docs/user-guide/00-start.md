# Start here

## What problem does the app solve?

A Meshtastic node tells you how strong a signal was when it arrived. But when a message has
travelled through other nodes, that signal figure describes **only the last jump**: the hop from the
node that forwarded the packet to you. It says nothing about the sender, or about the hops before.

So a normal node list can mislead you. A distant node may look "strong" because a neighbour
repeated its message loudly. A node may look "weak" because a different relay carried it.

Mesh Relay fixes the viewpoint. Every packet your node receives carries a one-byte hint, the
**relay byte**, that says which node sent it on to you. (Nodes fill this byte in from Meshtastic
firmware **2.6** on. See [Limits](10-limits.md) for what happens with older firmware.) The app groups packets by that byte and
shows, for each group:

- how many packets it carried and how many different nodes it carried them for,
- the **SNR** and **RSSI** of those packets: lowest, average, highest and latest,
- which nodes in your node database could be that relay.

In short: **you see who forwards to your spot, and how good each forwarder is from where you stand.**

## Where your position comes from

The app tags every measurement with where **you** were (the observer): coordinates and altitude. They
come either from your **phone's position** or from the position of **your own node**, depending on a
setting ([Settings](08-settings.md)). Phone altitude can be poor, especially among tall buildings and in
mountain valleys or gorges, where the phone sees few satellites.

## Two things to remember

1. **The app only sees reception.** A good signal from a relay means you hear that relay well.
   It does not prove the relay hears you.
2. **One byte is not a name.** Many nodes can end in the same byte, so the app often has
   several candidates for one relay. It tells you how many (`[2]`) and never pretends to know.

## Installation

Mesh Relay is published in its own **F-Droid repository**. F-Droid is a free app store for Android; a
repository is a catalogue of apps that you add to it.

1. **Install F-Droid** from <https://f-droid.org> if you do not have it yet. When Android asks, allow
   F-Droid to install apps.
2. **Add the repository.** In F-Droid open **Settings → Repositories** and tap **+** (add a repository).
   Enter this address:

   `https://cerocoder.github.io/mesh-relay-fdroid-repo/repo`

   You can also scan the QR code on the repository's page,
   <https://github.com/cerocoder/mesh-relay-fdroid-repo>, which holds the address and the key
   fingerprint.
3. **Check the fingerprint.** F-Droid shows the repository's key fingerprint before it adds the
   repository. It must read:

   `62:26:0B:66:B3:BD:EE:13:3A:28:46:D9:63:0F:E2:F3:F3:F8:57:D9:69:F7:C4:06:32:88:B2:08:A6:0F:8F:E9`

   If it differs, do not add the repository.
4. **Refresh the catalogue.** Pull down to refresh, or choose **Settings → Update repositories**.
5. **Install the app.** Search for **Mesh Relay** and tap **Install**. The first launch asks for
   Bluetooth permission ([Connecting](01-connecting.md)).

F-Droid will tell you when a new version appears in the repository.

Good to know:

- This is a **personal repository**, separate from the main F-Droid catalogue. Add it only if you trust
  the source, and always compare the fingerprint.
- The installed release build has **no demo devices**. They exist in debug builds only.
- If you installed the app another way before (for example a test build), F-Droid cannot update it,
  because the signing keys differ. Uninstall it first. Its settings are removed with it.

## Your first five minutes

1. Open the app and connect to your node ([Connecting](01-connecting.md)). To try the app first,
   use a demo device (debug builds only).
2. Open the **Relays** tab and leave it. Packets arrive as other nodes talk; how fast depends on how busy your mesh is.
3. Read the top card: the relay that carried the most packets, with its average SNR.
4. Tap **Reset** (the ⋮ menu) when you move the antenna or change something, so that old
   packets do not mix with new ones.
5. Wait until the same relay has carried at least 10–15 packets again, then compare its average SNR. Better or worse than before?

That loop (change one thing, reset, wait, compare) is the heart of the app. See
[Recipes](09-recipes.md).

## What is a good number?

There is no single answer, because it depends on your radio settings (the modem preset). A few
rules that hold in general:

- **Negative SNR is normal.** LoRa can decode packets weaker than the background noise.
- **Compare, don't judge.** Compare the same relay, with the same settings, at similar times of day.
- **Higher is better** for both numbers. SNR matters most for whether a packet can be decoded;
  RSSI alone can be high from interference.
- **A few packets prove little.** Wait until a relay has carried at least 10–15 packets (the number on its card) before trusting its average.

## Glossary

| Term | Meaning |
| :--- | :--- |
| **Relay byte** | The last byte of the node number of the node that forwarded a packet to you, shown in hex, for example `0x2a`. |
| **`0xff`** | The firmware uses the value `00` to mean "no relay information", so a node whose number ends in `00` announces itself as `0xff` instead. A node whose number really ends in `ff` also announces itself as `0xff`. The two cannot be told apart, so the candidates for `0xff` can be of both kinds. |
| **Candidate** | A node in your database whose number ends with the relay byte. The relay is one of them, or a node not yet known. |
| **SNR** | Signal-to-noise ratio, in dB. How much stronger the signal is than the noise. Higher is better. |
| **RSSI** | Received signal strength, in dBm. Closer to zero is stronger. |
| **Hop** | One forwarding step. Packets carry a limit so they don't circulate forever. |
| **Neighbour** | A node whose packets reach you directly, without being forwarded. |
| **Remote node** | A node whose packets reach you through a relay. |
| **Skip list** | Candidates you told the app to ignore, because you know they are not that relay. |
| **Node database** | The list of nodes your own node knows about. The app reads it when it connects. |

Next: [Connecting](01-connecting.md).
