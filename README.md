# Stream Shelf

Stream Shelf is an Android TV IPTV catalog browser and player for services that provide an Xtream Codes compatible API. It is an independent player and does not include channels, playlists, or account credentials.

## Features

- Dedicated Live TV, Movies, and Series sections designed for a TV remote.
- Search and title sorting across each section.
- Provider-category shelves and optional custom shelves for streaming availability, collection, year, language, and genre.
- Optional TMDB title matching for the first 60 movies and 60 series in a provider catalog per run. Matching is conservative; uncertain title matches are left unmatched.
- Streaming availability groups use TMDB's watch-provider data, powered by JustWatch, for a configurable two-letter region (default `IN`). Availability may include subscription, free, ad-supported, rental, and purchase services.
- Series episode browsing and Media3 playback for channels, movies, and episodes.
- Xtream provider URL, username, password, and TMDB API key stored encrypted with Android Keystore AES-GCM. A local metadata cache avoids repeating successful lookups for 30 days.
- Demo mode for browsing without connecting a provider.

Grouping by streaming service, metadata, and collections requires your own TMDB API key. Choose **MATCH TITLES** after loading your catalog; the first match can take a few minutes depending on the provider catalog and network. Grouping switches and sort order are saved on the device.

## TMDB and JustWatch attribution

Movie and TV metadata © TMDB. Streaming availability data is provided by JustWatch through TMDB. Availability results include a link to the relevant TMDB watch page. This product is not endorsed or certified by TMDB. See the [TMDB API terms and attribution requirements](https://developer.themoviedb.org/docs/faq) and [JustWatch attribution notice](https://developer.themoviedb.org/reference/tv-series-watch-providers).

## Build

Install JDK 17 and Android SDK Platform 36, then run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the APK on pushes and pull requests.

## Use

Install the APK on an Android TV, open **Stream Shelf**, and enter the provider's base URL, username, and password on the TV. The app contacts the provider's `player_api.php` endpoint. Prefer HTTPS; some providers offer only HTTP, which exposes account and stream requests to the network between the TV and provider. Provider credentials are not hard-coded or sent to this repository.

Enter your own TMDB API key and availability region in Settings to enable richer grouping. When you choose **MATCH TITLES**, titles may be sent to TMDB for matching. Provider availability comes from TMDB's JustWatch-powered watch-provider endpoint. It can be incomplete or out of date and does not guarantee that a title is playable through your IPTV provider.

## Privacy

Provider credentials and the TMDB API key are encrypted locally using an Android Keystore key. Catalog requests and playback URLs are sent to the provider entered by the user. Title searches and metadata lookups are sent to TMDB only after matching is requested. The app includes no analytics SDK.

## License

MIT. See [LICENSE](LICENSE).

