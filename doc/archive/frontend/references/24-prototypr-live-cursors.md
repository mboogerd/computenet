# Collaboration Tools and the Invasion of Live Cursors

- **Type**: Practitioner essay (Graeme Fulton, Prototypr)
- **Source**: https://prototypr.io/post/collaboration-tools-live-cursors
- **Accessed**: 2026-07-11

## What live cursors are for

Framed primarily as a *presence* and social-connection signal, not a productivity feature per se: "Multiplayer mode and live cursors create the feeling that you're sat with friends and teammates."

## Design evolution observed across tools

1. Plain colored carets/blocks (early Google Docs).
2. Branded cursor shapes (Figma).
3. User avatars/profile pictures replacing generic cursors (Niice, Pitch).
4. Live video-feed avatars (Pitch) — full presence, not just position.

> "Real-time Cursor feels like Stories feature all over again. Almost every app has it at this point" — i.e., by now it's closer to a baseline user expectation for collaborative tools than a differentiator.

## A concrete restraint pattern worth copying

Pitch defaults to regular cursors and only activates video-avatar mode selectively, "so avatars and faces don't distract from the content" — an explicit acknowledgment that maximal presence signaling isn't free; it competes for attention with the actual content.

## Relevance

If the agora backend supports multiple simultaneous users viewing/editing the same argument graph, presence indicators (who else is looking at or editing this claim right now) are a cheap way to make collaborative deliberation feel alive — but per Pitch's restraint pattern, presence UI should stay subtle by default so it doesn't compete with the argument content itself, which is already visually dense. See index: collaborative-editing, presence-indicators.
