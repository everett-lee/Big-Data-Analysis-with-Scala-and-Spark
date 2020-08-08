package wikipedia

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

case class WikipediaArticle(title: String, text: String) {
  /**
    * @return Whether the text of this article mentions `lang` or not
    * @param lang Language to look for (e.g. "Scala")
    */
  def mentionsLanguage(lang: String): Boolean = text.split(' ').contains(lang)

  def getLang(langs: List[String]): String = langs.find(lang => mentionsLanguage(lang)).getOrElse("None")

  def mapToArticlePairs(langs: List[String]): List[(String, WikipediaArticle)] =
    langs.filter(lang => this.mentionsLanguage(lang))
      .map(lang => (lang, this))

  def mapToIntPairs(langs: List[String]): List[(String, Int)] =
    langs.filter(lang => this.mentionsLanguage(lang))
      .map(lang => (lang, 1))
}

object WikipediaRanking extends WikipediaRankingInterface {

  val langs = List(
    "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
    "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  val conf: SparkConf = new SparkConf()
    .setMaster("local")
    .setAppName("wiki")

  val sc: SparkContext = new SparkContext(conf);
  // Hint: use a combination of `sc.parallelize`, `WikipediaData.lines` and `WikipediaData.parse`

  val wikiRdd: RDD[WikipediaArticle] = sc.parallelize(WikipediaData.lines
    .map(line => WikipediaData.parse(line))
  )

  /** Returns the number of articles on which the language `lang` occurs.
    * Hint1: consider using method `aggregate` on RDD[T].
    * Hint2: consider using method `mentionsLanguage` on `WikipediaArticle`
    */
  def occurrencesOfLang(lang: String, rdd: RDD[WikipediaArticle]): Int = {
    def secOp: (Int, WikipediaArticle) => Int = (accu: Int, v: WikipediaArticle) => {
      if (v.mentionsLanguage(lang)) accu + 1 else accu
    }

    def comOp: (Int, Int) => Int = (accu: Int, v: Int) => accu + v

    rdd.aggregate(0)(secOp, comOp)
  }

  /* (1) Use `occurrencesOfLang` to compute the ranking of the languages
   *     (`val langs`) by determining the number of Wikipedia articles that
   *     mention each language at least once. Don't forget to sort the
   *     languages by their occurrence, in decreasing order!
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangs(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    langs.map(lang => (lang, occurrencesOfLang(lang, rdd)))
      .sortWith((a, b) => a._2 > b._2)
  }

  /* Compute an inverted index of the set of articles, mapping each language
   * to the Wikipedia pages in which it occurs.
   */
  def makeIndex(langs: List[String], rdd: RDD[WikipediaArticle]): RDD[(String, Iterable[WikipediaArticle])] = {
    rdd.map(article => article.mapToArticlePairs(langs))
      .flatMap(pair => pair)
      .groupByKey()
  }

  /* (2) Compute the language ranking again, but now using the inverted index. Can you notice
   *     a performance improvement?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsUsingIndex(index: RDD[(String, Iterable[WikipediaArticle])]): List[(String, Int)] = {
    index.mapValues(article => article.size)
      .collect()
      .sortWith((a, b) => a._2 > b._2)
      .toList
  }

  /* (3) Use `reduceByKey` so that the computation of the index and the ranking are combined.
   *     Can you notice an improvement in performance compared to measuring *both* the computation of the index
   *     and the computation of the ranking? If so, can you think of a reason?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsReduceByKey(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    rdd.map(article => article.mapToIntPairs(langs))
      .flatMap(pair => pair)
      .reduceByKey((acc, a) => acc + a)
      .collect()
      .sortWith((a, b) => a._2 > b._2)
      .toList
  }

  def main(args: Array[String]): Unit = {

    /* Languages ranked according to (1) */
    val langsRanked: List[(String, Int)] = timed("Part 1: naive ranking", rankLangs(langs, wikiRdd))


    /* An inverted index mapping languages to wikipedia pages on which they appear */
    def index: RDD[(String, Iterable[WikipediaArticle])] = makeIndex(langs, wikiRdd)

    /* Languages ranked according to (2), using the inverted index */
    val langsRanked2: List[(String, Int)] = timed("Part 2: ranking using inverted index", rankLangsUsingIndex(index))


    /* Languages ranked according to (3) */
    val langsRanked3: List[(String, Int)] = timed("Part 3: ranking using reduceByKey", rankLangsReduceByKey(langs, wikiRdd))


    /* Output the speed of each ranking */
    println(timing)
    sc.stop()
  }

  val timing = new StringBuffer

  def timed[T](label: String, code: => T): T = {
    val start = System.currentTimeMillis()
    val result = code
    val stop = System.currentTimeMillis()
    timing.append(s"Processing $label took ${stop - start} ms.\n")
    result
  }
}
