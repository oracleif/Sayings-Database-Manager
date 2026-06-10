# Sayings Database Manager

An offline-first Android application designed to let you easily load, organize, and browse custom databases of sayings, fortunes, aphorisms, tech tips, quotes, etc.  This lets you create your own personalized "message of the day" (or "Saying of the Day") on your Android device with custom shortcuts, searchable browse page, and multiple databases!

## Features

- **Multi-Database Support**: Create, rename, delete, and switch between separate collections or categories built from text files you import.
- **Multiple text file format support**: Load from text files formatted with single-line entries, blank-line separated paragraphs, or *nix fortune cookie format with selectable delimiater character(s).
- **Robust CSV & JSON Import**: Load from raw CSV (with or without headers), standard nested/flat JSON arrays, newline-delimited JSON (NDJSON / JSON Lines), or mixed JSON stream content.
- **Flexible Field Mapping**: Map columns or keys dynamically from your CSV or JSON files to the sayings body and (optional) annotation.
- **Search & Filter**: Quickly retrieve entries by index # or filter using a case-insensitive, multi-string keyword search; once an entry is selected, swipe left/right to view next/previous entries.
- **Saying of the Day (SotD)**: Steps sequentially through your database to offer a new message every day (automatically wraps to entry #1 after last entry). Includes load-time offset tuning to start your daily message cycle exactly where you want.
- **Homescreen Shortcut**: Pin shortcuts on your home screen for any of your custom databases to get instant access (opens to Saying of the Day).

## License

This project is Free and Open Source Software (FLOSS) released under the **GNU General Public License v3 (GPL v3)**. 

See the [LICENSE](./LICENSE) file for the full terms and conditions.

### GPL v3 Compliance

All source files are copyrighted and can be redistributed or modified under the terms of the GNU GPL v3.
