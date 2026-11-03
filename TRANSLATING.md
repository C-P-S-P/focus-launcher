# Translating Focus

Every word Focus shows lives in Android string resources, so a new language needs no code changes.

## Add a language

1. Pick the resource qualifier: `de`, `fr`, `pt-rBR`, `zh-rTW`, `b+sr+Latn` and so on.
2. Copy every `app/src/main/res/values/strings*.xml` into a new folder
   `app/src/main/res/values-<qualifier>/`, keeping the file names.
3. Translate the text between the tags. Keep the key names, and leave out entries marked
   `translatable="false"`. Set `resources_locale` in `strings.xml` to your language tag
   (`de`, `pt-BR`): dates and month names are formatted in that language.
4. Keep every placeholder: `%1$s` is text, `%1$d` is a whole number. You may move them around in
   the sentence (that is what the numbers are for), but not drop or retype them.
5. `<plurals>` need every category your language uses (English: `one`, `other`; Polish: `one`,
   `few`, `many`, `other`). The categories are listed in the
   [CLDR plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html).
6. `format_*` strings are date patterns in `java.time.format.DateTimeFormatter` syntax. Translate
   them only to change the order or style; month and day names come from the system.
7. Optional: store listing text in `fastlane/metadata/android/<locale>/`.

That is all. The build lists every `values-*` folder as a supported language, so Android 13 and newer
offer it under Settings › System › Languages › App languages › Focus. Older versions follow the
phone's language.

## Rules of thumb

- Keep it as short as the English: lines on the home screen and in the app list must fit one line.
- XML escaping: `\'` for an apostrophe, `\"` for a double quote, `&amp;` for `&`, `&lt;` for `<`,
  `%%` for a literal percent sign in a string that has placeholders.
- Read the comments above a string; they say where it appears and what each placeholder holds.

## For developers

Never put user-visible text in Kotlin. Add it to the `strings_<area>.xml` of the screen (or to
`strings.xml` for services, the data layer and formatting helpers), then use `stringResource(...)` in
composables or `getString(...)` elsewhere. One sentence is one string with placeholders, never
glued from pieces, because word order differs between languages. Lint (`./gradlew :app:lintDebug`)
reports `MissingTranslation` for keys a translation has not caught up with yet.
