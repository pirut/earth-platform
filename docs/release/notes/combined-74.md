Earth Platform build 74 (draft notes, not released yet).

### Fixes
- Big mod packs on 4 GB of memory could run out of memory a few seconds after `/earth goto` or `/earth visit` and close with exit code -805306369 and no crash report (#179). Earth now watches the game's memory while it runs. When memory is nearly full it keeps less map data in memory and draws far terrain more slowly until memory frees up, and the first time this happens in a game you get one chat line saying how much memory is in use and how to give the game more. With plenty of memory nothing changes.
- On Windows, the Earth data cache sometimes logged `AccessDeniedException` while saving a downloaded piece, when another part of the game or a virus scanner had the same file open. The save now waits a moment and tries again.
