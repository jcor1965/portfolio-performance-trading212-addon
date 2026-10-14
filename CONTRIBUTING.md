# Contributing

Please open an issue before changing financial mappings. Add a minimal synthetic fixture and assert transaction types, net cash, withholding and quantities. Cover both `Dividend (Dividend)` and `Dividend (Interest)` when changing dividend handling.

Never commit personal broker exports, wallet XMLs, credentials, workstation paths or account identifiers. A private reconciliation was performed locally; its input data is deliberately absent from this repository.

Build with JDK 21 against the official 0.89.0 host and run both test commands in the README. Do not widen the host version range without testing installation, import, duplicate handling, save/reload, removal and updates.

Markdown tables, when added, must have visually aligned columns using spaces.
