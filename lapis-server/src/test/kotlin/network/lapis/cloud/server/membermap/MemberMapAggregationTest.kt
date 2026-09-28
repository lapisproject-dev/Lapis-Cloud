package network.lapis.cloud.server.membermap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private val CENTROIDS =
    PostalCodeCentroidIndex.parse(
        (
            listOf("postal_code,place_name,lat,lon", "38100,Braunschweig,52.2647,10.5233") +
                (30001..30001 + PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES).map { "$it,Testort,51.0,10.0" }
        ).asSequence(),
    )

class MemberMapAggregationTest :
    FunSpec({
        test("blank country + resolvable postal code -> mapped, with centroid data") {
            val response =
                aggregateMemberMap(
                    groups = listOf(AddressGroup(postalCode = "38100", country = null, count = 3)),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.total shouldBe 3
            response.mappedTotal shouldBe 3
            response.entries.single().placeName shouldBe "Braunschweig"
            response.entries.single().lat shouldBe 52.2647
        }

        test("'DE' and padded/mixed-case 'deutschland' both count as German") {
            val response =
                aggregateMemberMap(
                    groups =
                        listOf(
                            AddressGroup(postalCode = "38100", country = "DE", count = 1),
                            AddressGroup(postalCode = "38100", country = " deutschland ", count = 2),
                        ),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.mappedTotal shouldBe 3
            response.entries.single().count shouldBe 3
        }

        test(
            "a valid-shaped German postal code missing from the centroid index -> unresolvableGermanPostalCode, entry has no coordinates",
        ) {
            val response =
                aggregateMemberMap(
                    groups = listOf(AddressGroup(postalCode = "99999", country = "DE", count = 4)),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.unresolvableGermanPostalCode shouldBe 4
            response.mappedTotal shouldBe 0
            val entry = response.entries.single()
            entry.lat shouldBe null
            entry.lon shouldBe null
            entry.placeName shouldBe null
        }

        test("'3810' (4 digits), '38100a', and null postal code -> noPostalCode") {
            val response =
                aggregateMemberMap(
                    groups =
                        listOf(
                            AddressGroup(postalCode = "3810", country = "DE", count = 1),
                            AddressGroup(postalCode = "38100a", country = "DE", count = 1),
                            AddressGroup(postalCode = null, country = "DE", count = 1),
                        ),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.noPostalCode shouldBe 3
            response.mappedTotal shouldBe 0
            response.unresolvableGermanPostalCode shouldBe 0
        }

        test("'Österreich' + '1010' -> foreign, never noPostalCode") {
            val response =
                aggregateMemberMap(
                    groups = listOf(AddressGroup(postalCode = "1010", country = "Österreich", count = 2)),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.foreign shouldBe 2
            response.noPostalCode shouldBe 0
        }

        test("'France' + '75001' (valid German-shaped postal code) -> foreign, NEVER mapped or unresolvable") {
            val response =
                aggregateMemberMap(
                    groups = listOf(AddressGroup(postalCode = "75001", country = "France", count = 5)),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.foreign shouldBe 5
            response.mappedTotal shouldBe 0
            response.unresolvableGermanPostalCode shouldBe 0
        }

        test("centroids = null -> every valid DE postal code is unresolvable, invariant still holds, geodataAvailable = false") {
            val response =
                aggregateMemberMap(
                    groups =
                        listOf(
                            AddressGroup(postalCode = "38100", country = "DE", count = 7),
                            AddressGroup(postalCode = null, country = "DE", count = 1),
                            AddressGroup(postalCode = "1010", country = "AT", count = 1),
                        ),
                    centroids = null,
                    tilesAvailable = true,
                )
            response.geodataAvailable shouldBe false
            response.mappedTotal shouldBe 0
            response.unresolvableGermanPostalCode shouldBe 7
            response.noPostalCode shouldBe 1
            response.foreign shouldBe 1
            response.total shouldBe 9
        }

        test("the same postal code from two distinct input groups is summed into ONE entry") {
            val response =
                aggregateMemberMap(
                    groups =
                        listOf(
                            AddressGroup(postalCode = "38100", country = "DE", count = 2),
                            AddressGroup(postalCode = "38100", country = null, count = 3),
                        ),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.entries.size shouldBe 1
            response.entries.single().count shouldBe 5
        }

        test("entries sorted count desc, then postalCode asc") {
            val response =
                aggregateMemberMap(
                    groups =
                        listOf(
                            AddressGroup(postalCode = "38100", country = "DE", count = 2),
                            AddressGroup(postalCode = "99999", country = "DE", count = 5),
                            AddressGroup(postalCode = "10001", country = "DE", count = 5),
                        ),
                    centroids = CENTROIDS,
                    tilesAvailable = true,
                )
            response.entries.map { it.postalCode } shouldBe listOf("10001", "99999", "38100")
        }

        test("tilesAvailable / geodataAvailable pass through independently") {
            aggregateMemberMap(groups = emptyList(), centroids = CENTROIDS, tilesAvailable = false).tilesAvailable shouldBe false
            aggregateMemberMap(groups = emptyList(), centroids = CENTROIDS, tilesAvailable = true).geodataAvailable shouldBe true
            aggregateMemberMap(groups = emptyList(), centroids = null, tilesAvailable = true).geodataAvailable shouldBe false
        }

        test("invariant holds over 200 random group lists") {
            val random = Random(42)
            repeat(200) {
                val groups =
                    (1..random.nextInt(1, 20)).map {
                        val kind = random.nextInt(4)
                        val postalCode =
                            when (kind) {
                                0 -> "38100"
                                1 -> "99999"
                                2 -> if (random.nextBoolean()) null else "abc"
                                else -> "75001"
                            }
                        val country =
                            when (kind) {
                                3 -> "France"
                                else -> if (random.nextBoolean()) null else "DE"
                            }
                        AddressGroup(postalCode = postalCode, country = country, count = random.nextInt(1, 50))
                    }
                val response = aggregateMemberMap(groups = groups, centroids = CENTROIDS, tilesAvailable = true)
                response.total shouldBe
                    response.mappedTotal + response.unresolvableGermanPostalCode + response.noPostalCode + response.foreign
            }
        }
    })
