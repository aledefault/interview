# A local proxy for Forex rates

## Summary
This work takes the original exercise from a basic mock setup to something much closer to a real service.
Instead of relying on a dummy rates provider, it now connects to One-Frame to retrieve live exchange rates using *CachedRatesProvider*, an in-memory cache layer used by the application to stay within the One-Frame request limits while still serving rates that are fresh enough for the challenge (5 minutes).

## Improvements
* The biggest improvement is the decision to refresh all supported currency pairs at once, instead of calling the upstream API pair by pair. This keeps the solution simple and makes the quota problem much easier to manage.
* The cache serves most requests directly from memory, which makes the hot path faster and avoids unnecessary calls to One-Frame.
* With the current TTL of 180 seconds, the One-Frame quota usage would be limited to 480 requests per day, which is well below the 1000 requests-per-day limit.
* The public API was improved by returning clearer and more explicit errors.
* Some tests were added around the parts that contain the most logic and risk, although coverage is still limited due to time constraints.

## Caveat
The cache is local to a single process, which completely discards horizontal scaling for the API. This was a hard decision, but I wanted to keep the solution as simple as possible for the scope of the exercise.

My first choice would have been to use an external service or background process to keep the rates persisted and up to date. That would add more complexity in both development and deployment, but it would avoid blocking requests and cache stampedes entirely. Another alternative would have been to use something like Redis Redlock to coordinate rate refreshes.

## Considerations
1. I changed the main API port to `8081` to avoid collisions.
2. The token has been pushed to the repository to make running the project easier.