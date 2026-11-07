# Limits and troubleshooting

## What the app cannot tell you

- **Whether others hear you.** Everything here is about *reception*. A strong relay does not prove
  that the relay hears your node. Test the other direction with a message, or ask someone.
- **Exactly which node a relay is.** The relay byte is only one byte. Several nodes can share it, so
  the app shows candidates, never a certainty. You can narrow the list with **Skip** when you know
  better.
- **The sender's signal.** For packets that travelled several hops, SNR and RSSI describe only the
  last hop, from the relay to you.
- **What counts as good for your radio settings.** The lowest decodable SNR depends on your modem
  preset. Compare numbers within the same preset.
- **The route a message took.** The app sees one forwarding step. It cannot reconstruct the whole path.
- **A forecast.** It measures what actually reaches your node while you watch. It does not predict
  coverage in other places or at other times.

## Firmware and the relay byte

- The relay byte exists in Meshtastic firmware **2.6** and newer. Your node needs it to pass the byte to
  the app, and a forwarding node needs it to write the byte into the packet.
- A packet forwarded by a node with older firmware arrives without a relay byte. The app cannot see that
  a relay was involved, and counts it as a **direct** packet. A node that looks like a neighbour may
  then be a distant node forwarded by an old-firmware relay.
- Packets from very old firmware (before 2.3) carry no hop information at all, so they are also treated
  as direct.

## What the app does not do

- It does not send messages, change your node's settings or join channels.
- It does not keep statistics after you exit. Use **Export data** and **Save nodes** for that.
- It needs Bluetooth Low Energy and connects to one node at a time.

## Troubleshooting

| Symptom | Likely cause and fix |
| :--- | :--- |
| No devices found | Node off or out of range, Bluetooth off, or the node is already connected to another phone. Switch the other connection off. |
| Pairing fails | Type the code from the node's screen within two minutes. Try again; remove the old pairing in Android's Bluetooth settings if it keeps failing. |
| The connection drops often | The node is at the edge of Bluetooth range, or its battery is low. Move closer. |
| **No relays yet** for a long time | The mesh is quiet, or your node hears no forwarded traffic. Wait, or check the antenna. |
| A relay shows `[3]` and no name | Three nodes share that byte. Open its details and use **Skip** on the ones you know are not it. |
| **No matching nodes** | The relay has not announced itself yet. Wait, then reload the node database. |
| The collection stops when I leave the app | Turn on **Collect in background** in Settings. |
| The screen turns off during a test | Turn on **Keep screen on** in Settings. |
| The 🌐 button is grey | No position is available. Turn on **Use phone location**, or make sure your node knows its position. |
| Numbers look different from another app | Other apps may count all packets together. Here, relayed and direct packets are counted apart. |

## Good habits

- Wait for at least 10–15 packets from a relay before trusting its average.
- Change one thing at a time. Tap **Reset** between changes.
- Test at similar times of day. Traffic and noise vary.
- Keep notes, and use bookmarks for places.
- Respect local radio rules and power limits, and check your region setting.
