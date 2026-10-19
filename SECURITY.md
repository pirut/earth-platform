# Security

Earth Platform downloads public Earth data (elevation, land cover, climate and water) over HTTPS from the hosts listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), keeps it in `earthplatform/cache` inside the game folder, and reads it there. Those downloads are its only network traffic: it has no telemetry and uploads nothing. As with any download, the data hosts can see your IP address and which map tiles you fetch, which shows roughly where in the world you play.

## Reporting a problem

Please report security problems privately through GitHub: open the [Security tab](https://github.com/pirut/earth-platform/security/advisories/new) and choose **Report a vulnerability**. Don't open a public issue for them.

Useful details: the Earth Platform version, what an attacker could do, and steps or a file that show it. You'll get an answer on the report, and the fix will be credited to you if you'd like.

## Supported versions

Only the latest release gets fixes.
