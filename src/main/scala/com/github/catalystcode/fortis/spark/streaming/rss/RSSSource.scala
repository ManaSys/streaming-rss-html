package com.github.catalystcode.fortis.spark.streaming.rss

import java.net.URL
import java.util.Date

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.{SyndFeedInput, XmlReader}

import scala.collection.JavaConversions._
import scala.collection.mutable

private[rss] class RSSSource(feedURLs: Seq[URL], requestHeaders: Map[String, String]) extends Serializable {

  val connectTimeout: Int = sys.env.getOrElse("RSS_ON_DEMAND_STREAM_CONNECT_TIMEOUT", "500").toInt
  val readTimeout: Int = sys.env.getOrElse("RSS_ON_DEMAND_STREAM_READ_TIMEOUT", "1000").toInt

  private[rss] var lastIngestedDates = mutable.Map[URL, Long]()

  def reset(): Unit = {
    lastIngestedDates.clear()
    feedURLs.foreach(url=>{
      lastIngestedDates.put(url, Long.MinValue)
    })
  }

  def fetchEntries(): Seq[RSSEntry] = {
    fetchFeeds()
      .filter(_.isDefined)
      .flatMap(optionPair=>{
        val url = optionPair.get._1
        val feed = optionPair.get._2

        val source = RSSFeed(
          feedType = feed.getFeedType,
          uri = feed.getUri,
          title = feed.getTitle,
          description = feed.getDescription,
          link = feed.getLink
        )

        feed.getEntries
          .filter(entry=>{
            val date = Math.max(safeDateGetTime(entry.getPublishedDate), safeDateGetTime(entry.getUpdatedDate))
            lastIngestedDates.get(url).isEmpty || date > lastIngestedDates(url)
          })
          .map(feedEntry=>{
            val entry = RSSEntry(
              source = source,
              uri = feedEntry.getUri,
              title = feedEntry.getTitle,
              links = feedEntry.getLinks.map(l => RSSLink(href = l.getHref, title = l.getTitle)).toList,
              content = feedEntry.getContents.map(c => RSSContent(contentType = c.getType, mode = c.getMode, value = c.getValue)).toList,
              description = feedEntry.getDescription match {
                case null => null
                case d => RSSContent(
                  contentType = d.getType,
                  mode = d.getMode,
                  value = d.getValue
                )
              },
              enclosures = feedEntry.getEnclosures.map(e => RSSEnclosure(url = e.getUrl, enclosureType = e.getType, length = e.getLength)).toList,
              publishedDate = safeDateGetTime(feedEntry.getPublishedDate),
              updatedDate = safeDateGetTime(feedEntry.getUpdatedDate),
              authors = feedEntry.getAuthors.map(a => RSSPerson(name = a.getName, uri = a.getUri, email = a.getEmail)).toList,
              contributors = feedEntry.getContributors.map(c => RSSPerson(name = c.getName, uri = c.getUri, email = c.getEmail)).toList
            )
            markStored(entry, url)
            entry
          })
      })
  }

  private[rss] def fetchFeeds(): Seq[Option[(URL, SyndFeed)]] = {
    feedURLs.map(url=>{
      try {
        val connection = url.openConnection()
        connection.setConnectTimeout(connectTimeout)
        connection.setReadTimeout(readTimeout)
        val reader = new XmlReader(connection, requestHeaders)
        val feed = new SyndFeedInput().build(reader)
        Some((url, feed))
      } catch {
        case e: Exception => None
      }
    })
  }

  private def markStored(entry: RSSEntry, url: URL): Unit = {
    val date = entry.updatedDate match {
      case 0 => entry.publishedDate
      case _ => entry.updatedDate
    }
    lastIngestedDates.get(url) match {
      case Some(lastIngestedDate) => if (date > lastIngestedDate) {
          lastIngestedDates.put(url, date)
      }
      case None => lastIngestedDates.put(url, date)
    }
  }

  private def safeDateGetTime(date: Date): Long = {
    Option(date).map(_.getTime).getOrElse(0)
  }

}
