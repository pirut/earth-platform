# Prompt: build the "Green Canyon" YouTube Short for Earth Platform

You are an elite YouTube Shorts editor and a capable engineer. Build a funny, highly rewatchable vertical Short (1080x1920, 30 fps, 20-25 s) for **Earth Platform**, a Minecraft mod that generates the real Earth at 1:1 scale from elevation data.

## Repo
- GitHub: https://github.com/pirut/earth-cubic-platform
- Branch to work on and push to: `claude/project-thread-dubj6e` (push only to this branch; never merge to main or open a PR into main)
- Clone: `git clone -b claude/project-thread-dubj6e https://github.com/pirut/earth-cubic-platform`

### What already exists (start here, reuse it)
- `docs/release/trailer/tools/green2.py`: the current v2 edit (18.4 s). Read it first. It contains the full beat sequence, chat panel, comment card, and synthesized comedy SFX (key clicks, AI blip, error buzz, record scratch, whoosh, impact, reveal music).
- `docs/release/trailer/tools/green.py`: v1 edit; has `comment_card()` (recreated YouTube comment + creator reply "On it" + heart) and `chat_panel()` (generic "AI chat", typing, dots).
- `docs/release/trailer/tools/shorts.py`: shared helpers (`big`, `soft`, `put_top`, `POP`, `brand_bar`, `make_audio` with loudnorm to -14 LUFS, `encoder` piping raw frames to ffmpeg libx264, `DISCLAIMER`, fonts).
- `docs/release/trailer/tools/loop.py`: `whooshes`, `read_wav`; `sound3.py` shows the house style for original synthesized music and loop-safe mixing (render over 3 periods, keep the middle).
- Footage: `docs/release/trailer/hooks/clips/b48-g-*.mp4` (desk-walk, desk-type, grand_canyon, greenland, waimea, green_canyon), shot from `docs/release/trailer/hooks/green.cine`.
- Current outputs for reference: `docs/release/videos/hooks/b48-green-canyon.mp4` (v1) and `b48-green-canyon-v2.mp4` (v2).
- Optional reshoot: the in-game dev camera is `minecraft/src/main/java/io/earthplatform/minecraft/cubic/play/client/Cinema.java`, enabled with `-Dearthplatform.cinema=<script.cine>`. Directives: `shot, cam, look, at, run, fov, settle, warm, hold`; options `third=1` (third-person behind the player), `glide=1` (elytra), `type=1` (typing arm swings), `^H` heights. See green.cine for working examples and coordinates.

## The story
Origin: a real YouTube comment, "Green canion pls" (by @axoltwatel). The creator replied "On it" and hearted it. The joke: the character walks up to a desk in Minecraft, asks an AI chat what "green canion" means, and the AI keeps guessing wrong places. The character flies over each one in third person, questioning it, until finally reaching **Green Canyon, Utah**.

Beats (keep this escalation, tighten as you see fit):
1. **Hook (0-2 s):** character walks to the desk. On-screen: "Someone asked me to generate Green Canyon in Minecraft…" plus the comment card, reply "On it" pops with a heart.
2. **Ask 1:** types "what's a green canion" → AI: "Grand Canyon!" → fly over the Grand Canyon. Text: "Grand Canyon?" / "...it's RED." (error buzz)
3. **Ask 2:** types "no. GREEN" → AI: "Greenland!" → Greenland ice. Text: "Greenland?" / "...that's ICE." (record scratch)
4. Optional third wrong guess (Waimea Canyon, Hawaii footage exists) if it stays funny and fast.
5. **Ask 3:** types "a canyon literally NAMED green" → AI: "Green Canyon, Utah."
6. **Reveal:** glide into Green Canyon. Big title "GREEN CANYON, UTAH 🌎" and "real terrain, every block at 1:1 scale". Music swells.
7. **Loop ending (last ~1.7 s):** "Where should I go next?" / "comment a real place ↓", cut so the last frame flows into the first.

Editing goals: punchy cuts (no shot over ~2.5 s except the reveal), text pops on beats, readable within the safe zone (keep text away from the right-side Shorts UI and the bottom caption area), comedy timing on every wrong answer, satisfying payoff, seamless loop.

## Hard rules
- Say "coming soon" only. No Modrinth, CurseForge or any platform names. Nothing claimable or downloadable.
- Never say "Minecraft Earth". The mod's name is **Earth Platform**.
- No Mojang logos or Minecraft game sounds/music. All music and SFX must be original (synthesized in code) or verifiably cleared; no Google Earth, Street View or other copyrighted imagery. Public-domain NPS photos only, credited.
- Show the disclaimer somewhere readable: "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."
- The AI chat must be generic ("AI chat"), not branded as any real product.
- No real cities or towns shown or named.
- US spelling; give ft/mi alongside meters if you show numbers.
- Audio: master to -14 LUFS integrated, about -1 dBTP; check for clipping.
- Commit messages must not include any AI model names. End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_015fiswpx9oroBZZSPXKpcLv
  ```

## Tools/environment notes
- Python 3 with Pillow and numpy (scipy optional), ffmpeg. Run tools from the repo root.
- A cloud render of the game (if you reshoot) needs Java 21, Xvfb, and runs without shaders at about 0.35-0.7 s per frame; the offline client has the default skin.

## Deliverables
1. The final MP4 at `docs/release/videos/hooks/b48-green-canyon-v3.mp4`, plus the edit script as `docs/release/trailer/tools/green3.py`. Commit and push to `claude/project-thread-dubj6e`.
2. Exact runtime, a short summary of what changed vs v2, 3 title options, a description with hashtags (including the disclaimer and "coming soon"), and an honest list of remaining weaknesses.

Known weak spots in v2 worth fixing if you can: default skin and no shaders in the footage, Green Canyon reads like a generic wooded valley (frame it so the canyon walls show), and Greenland is a plain white screen (find a shot with visible ice texture, coast or fjord).
