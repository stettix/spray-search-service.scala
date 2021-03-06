package com.blinkbox.books.search

import java.io.IOException
import scala.concurrent.Future
import scala.concurrent.duration._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.junit.runner.RunWith
import org.mockito.Matchers
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.{ BeforeAndAfter, FunSuite }
import org.scalatest.mock.MockitoSugar
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes._
import spray.testkit.ScalatestRouteTest

import com.blinkbox.books.spray.Paging._
import SearchApi._

@RunWith(classOf[JUnitRunner])
class SearchApiTests extends FunSuite with BeforeAndAfter with ScalatestRouteTest with MockitoSugar with SearchApi {

  override def service = mockService
  override val apiConfig = ApiConfig("localhost", 8080, "service/search", 5.seconds, "*", 100.seconds, 200.seconds)

  var mockService: SearchService = _

  override implicit def actorRefFactory = system

  val isbn = "1234567890123"

  val searchResults = BookSearchResult(42, Seq("suggested search"), List(
    Book("9781443414005", "Bleak House", List("Charles Dickens")),
    Book("9780141920061", "Hard Times", List("Charles Dickens"))))

  val suggestions = List(
    BookSuggestion("9781443414005", "Bleak House", List("Charles Dickens")),
    AuthorSuggestion("1d1f0d88a461e2e143c44c7736460c663c27ef3b", "Charles Dickens"),
    BookSuggestion("9780141920061", "Hard Times", List("Charles Dickens")))

  val similar = BookSearchResult(101, Seq(), List(
    Book("9781443414005", "Block House", List("Charles Smith")),
    Book("9780141920061", "Happy Times", List("Charles Smith"))))

  before {
    mockService = mock[SearchService]

    // Default mocked behaviour: return results for any query.
    doReturn(Future(searchResults)).when(service).search(anyString, anyInt, anyInt, any[SortOrder])
    doReturn(Future(suggestions)).when(service).suggestions(anyString, anyInt, anyInt)
    doReturn(Future(similar)).when(service).findSimilar(anyString, anyInt, anyInt)
  }

  test("simple search for book") {
    Get("/search/books?q=some+words") ~> route ~> check {
      assert(status == OK &&
        contentType.value == "application/vnd.blinkboxbooks.data.v1+json; charset=UTF-8" &&
        header("Access-Control-Allow-Origin").get.value == apiConfig.corsOrigin &&
        header("Cache-Control").get.value == s"public, max-age=${apiConfig.searchMaxAge.toSeconds}")

      // Check performed query, including default parameters.
      verify(service).search("some words", 0, 50, SortOrder("RELEVANCE", true))

      // Just this once, check the response against the full text of the expected JSON.
      val expectedJson =
        """{
    "type": "urn:blinkboxbooks:schema:search",
    "id": "some words",
    "numberOfResults": 42,
    "suggestions": ["suggested search"],
    "books": [
        {
            "id": "9781443414005",
            "title": "Bleak House",
            "authors": [
                "Charles Dickens"
            ]
        },
        {
            "id": "9780141920061",
            "title": "Hard Times",
            "authors": [
                "Charles Dickens"
            ]
        }
    ],
    "links": [
        {
            "rel": "this",
            "href": "service/search/books?count=50&offset=0"
        }
    ]
    }"""
      // Compare normalised JSON string representations.
      assert(parse(body.data.asString).toString == parse(expectedJson).toString, "Got: \n" + body.data.asString)
    }
  }

  test("search for book with all parameters") {
    val (offset, count) = (5, 10)

    // Set up mock to return search results for expected offset and count only.
    doReturn(Future(searchResults)).when(service)
      .search(anyString, Matchers.eq(offset), Matchers.eq(count), any[SortOrder])

    Get(s"/search/books?q=some+words&count=$count&order=POPULARITY&desc=false&offset=$offset") ~> route ~> check {
      assert(status == OK &&
        contentType.value == "application/vnd.blinkboxbooks.data.v1+json; charset=UTF-8")

      // Check request parameters were picked up correctly.
      verify(service).search("some words", offset, count, SortOrder("POPULARITY", false))

      // Check that the JSON response is correct
      val result = parse(body.data.asString).extract[QuerySearchResult]
      assert(result.numberOfResults == searchResults.numberOfResults)

      // Check the expected links, ignoring their order in the returned list.
      val links = result.links.groupBy(_.rel).mapValues(_.head.href)
      assert(links == Map(
        "this" -> s"service/search/books?count=$count&offset=$offset",
        "next" -> s"service/search/books?count=$count&offset=${offset + count}",
        "prev" -> s"service/search/books?count=$count&offset=0"))
    }
  }

  test("returns empty list for search query that matches nothing") {
    doReturn(Future(BookSearchResult(0, Seq(), List())))
      .when(service).search(anyString, anyInt, anyInt, any[SortOrder])

    Get("/search/books?q=unmatched&count=10") ~> route ~> check {
      assert(status == OK)
      val result = parse(body.data.asString).extract[QuerySearchResult]
      assert(result.numberOfResults == 0 &&
        result.links.size == 1 &&
        result.links(0) == PageLink("this", s"service/search/books?count=10&offset=0"))
    }
  }

  test("search with missing query parameter") {
    Get("search/books") ~> route ~> check {
      assert(!handled)
    }
  }

  test("search with missing query parameter and one valid parameter") {
    Get("search/books?limit=10") ~> route ~> check {
      assert(!handled)
    }
  }

  test("error returned when we fail to perform search on back-end") {
    for (
      (exception, excectedCode) <- Map(
        new IOException("Test exception") -> InternalServerError,
        new IndexOutOfBoundsException("Test exception") -> InternalServerError,
        new IllegalArgumentException("Test exception") -> BadRequest)
    ) {
      // Return failure from mock service.
      doReturn(Future(throw exception)).when(service).search(anyString, anyInt, anyInt, any[SortOrder])
      Get("/search/books?q=some+query") ~> route ~> check {
        assert(status == excectedCode)
      }
    }
  }

  test("simple query for suggestions") {
    Get("/search/suggestions?q=foo") ~> route ~> check {
      assert(status == OK &&
        contentType.value == "application/vnd.blinkboxbooks.data.v1+json; charset=UTF-8" &&
        header("Access-Control-Allow-Origin").get.value == apiConfig.corsOrigin &&
        header("Cache-Control").get.value == s"public, max-age=${apiConfig.autoCompleteMaxAge.toSeconds}")

      // TODO!
      //      val result = parse(body.data.asString).extract[SuggestionsResult]
      //      assert(result.items == suggestions, "Got: " + body.data.asString)
    }
  }

  test("simple query for suggestions with query parameters") {
    val (offset, count) = (5, 15)

    Get(s"/search/suggestions?q=foo&offset=$offset&count=$count") ~> route ~> check {
      assert(status == OK)

      // Check query parameters in request.
      verify(service).suggestions("foo", offset, count)
    }
  }

  test("simple query for similar books") {
    Get(s"/search/books/$isbn/similar") ~> route ~> check {
      assert(status == OK &&
        header("Access-Control-Allow-Origin").get.value == apiConfig.corsOrigin &&
        header("Cache-Control").get.value == s"public, max-age=${apiConfig.searchMaxAge.toSeconds}")

      // Check performed query, including default parameters.
      verify(service).findSimilar(isbn, 0, 10)

      // Check returned results.
      val result = parse(body.data.asString).extract[SimilarBooksSearchResult]
      assert(result.numberOfResults == similar.numberOfResults &&
        result.books == similar.books &&
        result.suggestions == similar.suggestions)
    }
  }

  test("query for similar books with query parameters") {
    val (offset, count) = (2, 18)
    Get(s"/search/books/$isbn/similar?offset=$offset&count=$count") ~> route ~> check {
      assert(status == OK)

      // Check performed query, including default parameters.
      verify(service).findSimilar(isbn, offset, count)

      // Check returned results.
      val str = body.data.asString
      val result = parse(body.data.asString).extract[SimilarBooksSearchResult]
      assert(result.numberOfResults == similar.numberOfResults &&
        result.books == similar.books &&
        result.suggestions == similar.suggestions)
    }
  }

  test("request for similar books with invalid ISBN") {
    for (id <- Seq("123456789012", "12345678901234", "xyz"))
      Get(s"/search/books/$id/similar") ~> route ~> check {
        assert(!handled)
      }
  }

  test("invalid request for similar books, with unwanted slash at end of URL") {
    Get("/search/books/") ~> route ~> check {
      assert(!handled)
    }
  }

  test("invalid request for similar books, with unwanted path elements at end of URL") {
    Get("/search/books/12345/similar/other") ~> route ~> check {
      assert(!handled)
    }
  }

}
