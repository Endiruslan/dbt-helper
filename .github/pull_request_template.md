## Problem

<!-- A problem you actually ran into, or a linked issue: what is wrong or missing, and how to reproduce it.
     PRs based only on an AI or code scan, with no real problem behind them, are closed. -->

## Change

<!-- What you changed and why this approach. Mention anything a reviewer should look at closely. -->

## Testing

<!-- IDE + version, dbt version, project size. What you did, what you saw. Screenshots/GIF for UI changes. -->

- [ ] Tested in a real IDE with the built ZIP, not only in `runIde`
- [ ] Checked again after an IDE restart
- [ ] Settings changes (if any) apply without a restart, and Runner commands use the same files as the plugin
- [ ] `./gradlew buildPlugin` passes with no new warnings; `verifyPlugin` run, or skipped because: …
- [ ] No new exceptions from `com.dbthelper` in `idea.log`
- [ ] AI-assisted (fine, see [CONTRIBUTING.md](https://github.com/Endiruslan/dbt-helper/blob/main/CONTRIBUTING.md#ai-assisted-contributions))

## Changelog line

<!-- One line for the release notes, e.g. "Fixed: …". We add it at release time. -->

See [CONTRIBUTING.md](https://github.com/Endiruslan/dbt-helper/blob/main/CONTRIBUTING.md) for details.
