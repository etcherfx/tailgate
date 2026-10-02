# Contributing to Tailgate

Bug reports, fixes and support for new Minecraft releases are welcome. For a new feature or a large change, open an issue first so we can agree on the approach before you build it.

## Reporting issues

Search existing issues first, then use the bug report or feature request form. For a bug, include your Tailgate, Minecraft and loader versions, the steps that trigger it, and the relevant part of `logs/latest.log`. Check logs and screenshots before sharing them, and remove server addresses you don't want public.

Report security problems privately, as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Development

The build, test and runtime-test commands are in the README's [Development](README.md#development) section, and [AGENTS.md](AGENTS.md) lists them cheapest first. Before opening a pull request, `./gradlew build` must pass, along with a build of every node your change touches (`-Ptailgate.nodes=<node>,<node>`). CI then builds every node and launches a smoke set of releases.

## Pull requests

- Keep each pull request to one focused change, and fill in the pull request template.
- Add or update `core/` tests for logic changes.
- For UI changes, build the nodes on each side of every version cutoff you touch, and run `runtimeTest` on at least one of them.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`fix(core): ...`, `feat(ui): ...`) and carry a DCO sign-off: commit with `git commit -s`.

## Releasing

Maintainers only.

1. Set `mod.version` in `gradle.properties` to the new version, following [Semantic Versioning](https://semver.org/).
2. Write the release notes in `.github/release-notes/vX.Y.Z.md`.
3. Commit both as `chore(release): vX.Y.Z`, then tag and push:

   ```sh
   git tag -a vX.Y.Z -m vX.Y.Z
   git push origin master vX.Y.Z
   ```

The tag runs the Build workflow, which fails unless the tag matches `mod.version` and the notes file exists. It builds every node, launches every release with the jar, and publishes `tailgate-X.Y.Z.jar` as the GitHub release `Tailgate vX.Y.Z`. A tag with a `-` (like `v0.2.0-beta.1`) becomes a pre-release.

To check a release first, run the Build workflow from the Actions tab with **Release dry run** selected. It does everything except publish.

## Licensing

By contributing, you agree that your contributions are licensed under the project's [GNU Lesser General Public License v3.0](LICENSE).
