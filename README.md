# Stream Shelf

Stream Shelf is an Android TV IPTV catalog browser and player for services that provide an Xtream Codes compatible API. It organizes the provider's Live TV, movie, and series categories into remote-friendly shelves. It is an independent player; it does not include IPTV channels, playlists, or account credentials.

## Current features

- Android TV launcher entry point and D-pad-focused Compose interface.
- Poster artwork from provider stream icons and TMDB matches.
- Xtream API import for live channels, movies, series, and series episodes.
- Media3 playback for live channels, movies, and episodes.
- Provider categories shown as separate shelves.
- Provider URL, username, and password entered on-device and encrypted with Android Keystore AES-GCM.
- Optional TMDB title matching for up to the first 15 movies and 15 series, with year, language, and genre shelves. TMDB is not required to connect or play.
- Demo mode for browsing the interface without a provider.

This is an early MVP. It does not yet provide user-defined streaming-service labels, TMDB collections or streaming-provider shelves, episode resume, EPG, parental controls, offline downloads, or manual TMDB match correction. Matching covers up to the first 15 movies and 15 series in the provider response, and uncertain title matches are skipped. Provider category names are the initial shelf grouping; TMDB genre, year, and language shelves appear after the user runs matching.

## Build

Install JDK 17 and Android SDK Platform 36, then run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds this APK for pushes and pull requests and publishes it as a 30-day workflow artifact.

## Use

Install the APK on an Android TV, open **Stream Shelf**, and enter the provider's base URL, username, and password on the TV. The app contacts the provider's `player_api.php` endpoint. Prefer HTTPS; some IPTV providers offer only HTTP, which exposes account and stream requests to the network between the TV and provider. Secrets are not hard-coded or sent to this repository.

TMDB matching is optional. To use it, add your own TMDB API key while connecting the provider, then choose **MATCH TMDB** from the library. If you use TMDB data, follow its [API terms and attribution requirements](https://developer.themoviedb.org/docs/faq). This product is not endorsed or certified by TMDB.

## Privacy

Provider credentials are stored locally using an Android Keystore key. Catalog requests and playback URLs are sent to the provider entered by the user. If a TMDB key is added, title searches may be sent to TMDB. The app includes no analytics SDK.

## License

MIT. See [LICENSE](LICENSE).

