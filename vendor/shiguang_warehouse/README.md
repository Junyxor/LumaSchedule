# Shiguang adapter snapshot

This directory is synchronized from [`XingHeYuZhuan/shiguang_warehouse`](https://github.com/XingHeYuZhuan/shiguang_warehouse).

The upstream adapter warehouse is MIT licensed. LumaSchedule preserves the upstream license and maintainer metadata. The GitHub Actions workflow `sync-shiguang.yml` refreshes `index/`, `resources/`, `LICENSE`, and `SNAPSHOT.json` without rewriting adapter source files.

A checkout may contain only this bootstrap file before the first synchronization. Run:

```bash
npm run adapters:sync
```

or run the **Sync Shiguang adapters** GitHub Actions workflow.
