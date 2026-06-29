/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.TimeUnit

/**
 * Bundled "something every day" content for the home-screen daily tile: a quote, a
 * word, or a trivia question. Everything is local (no network, no GMS) so the tile
 * is instant and works offline; the item is chosen deterministically from the epoch
 * day, so it's stable for the whole day and rotates at midnight.
 */
object DailyContent {

  enum class Mode { OFF, QUOTE, WORD, TRIVIA }

  fun modeOf(key: String): Mode = when (key) {
    "quote" -> Mode.QUOTE
    "word" -> Mode.WORD
    "trivia" -> Mode.TRIVIA
    else -> Mode.OFF
  }

  fun keyOf(mode: Mode): String = when (mode) {
    Mode.QUOTE -> "quote"
    Mode.WORD -> "word"
    Mode.TRIVIA -> "trivia"
    Mode.OFF -> "off"
  }

  data class Quote(val text: String, val author: String)
  data class Word(val word: String, val pronunciation: String, val definition: String)
  data class Trivia(val question: String, val answer: String)

  /** Days since the Unix epoch, in the device's default zone (good enough — a fixed
   * Portal doesn't travel). Used as the rotation index. */
  fun epochDay(nowMillis: Long = System.currentTimeMillis()): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(nowMillis)
    return TimeUnit.MILLISECONDS.toDays(nowMillis + offset)
  }

  fun quoteOfDay(nowMillis: Long = System.currentTimeMillis()): Quote =
      QUOTES[(epochDay(nowMillis) % QUOTES.size).toInt()]

  fun wordOfDay(nowMillis: Long = System.currentTimeMillis()): Word =
      WORDS[(epochDay(nowMillis) % WORDS.size).toInt()]

  fun triviaOfDay(nowMillis: Long = System.currentTimeMillis()): Trivia =
      TRIVIA[(epochDay(nowMillis) % TRIVIA.size).toInt()]

  private val QUOTES = listOf(
      Quote("The best way to predict the future is to invent it.", "Alan Kay"),
      Quote("Simplicity is the ultimate sophistication.", "Leonardo da Vinci"),
      Quote("What we think, we become.", "Buddha"),
      Quote("It always seems impossible until it's done.", "Nelson Mandela"),
      Quote("The only way to do great work is to love what you do.", "Steve Jobs"),
      Quote("In the middle of difficulty lies opportunity.", "Albert Einstein"),
      Quote("Well done is better than well said.", "Benjamin Franklin"),
      Quote("Whatever you are, be a good one.", "Abraham Lincoln"),
      Quote("The journey of a thousand miles begins with one step.", "Lao Tzu"),
      Quote("Happiness depends upon ourselves.", "Aristotle"),
      Quote("Turn your wounds into wisdom.", "Oprah Winfrey"),
      Quote("Do what you can, with what you have, where you are.", "Theodore Roosevelt"),
      Quote("Quality is not an act, it is a habit.", "Aristotle"),
      Quote("The future belongs to those who believe in their dreams.", "Eleanor Roosevelt"),
      Quote("Everything you can imagine is real.", "Pablo Picasso"),
      Quote("Act as if what you do makes a difference. It does.", "William James"),
      Quote("Life is really simple, but we insist on making it complicated.", "Confucius"),
      Quote("A year from now you may wish you had started today.", "Karen Lamb"),
      Quote("The mind is everything. What you think you become.", "Buddha"),
      Quote("Patience is bitter, but its fruit is sweet.", "Aristotle"),
      Quote("Little by little, one travels far.", "J.R.R. Tolkien"),
      Quote("Courage is grace under pressure.", "Ernest Hemingway"),
      Quote("Stay hungry, stay foolish.", "Steve Jobs"),
      Quote("Energy and persistence conquer all things.", "Benjamin Franklin"),
      Quote("The secret of getting ahead is getting started.", "Mark Twain"),
      Quote("Done is better than perfect.", "Sheryl Sandberg"),
      Quote("Be kind whenever possible. It is always possible.", "Dalai Lama"),
      Quote("If you can dream it, you can do it.", "Walt Disney"),
      Quote("Make each day your masterpiece.", "John Wooden"),
      Quote("The harder you work for something, the greater you'll feel when you achieve it.", "Anonymous"),
  )

  private val WORDS = listOf(
      Word("ephemeral", "ih-FEM-er-uhl", "Lasting for a very short time."),
      Word("petrichor", "PET-ri-kor", "The pleasant smell of earth after rain."),
      Word("serendipity", "ser-uhn-DIP-i-tee", "Finding something good without looking for it."),
      Word("ineffable", "in-EF-uh-buhl", "Too great to be expressed in words."),
      Word("sonder", "SON-der", "The realization that each passerby has a life as vivid as your own."),
      Word("limerence", "LIM-er-uhns", "The state of being infatuated with another person."),
      Word("halcyon", "HAL-see-uhn", "Denoting a period that was idyllically happy and peaceful."),
      Word("susurrus", "soo-SUR-uhs", "A whispering or rustling sound."),
      Word("mellifluous", "muh-LIF-loo-uhs", "A sound that is sweet and smooth to hear."),
      Word("eloquent", "EL-uh-kwuhnt", "Fluent or persuasive in speaking or writing."),
      Word("luminous", "LOO-mi-nuhs", "Full of or shedding light; bright or shining."),
      Word("quintessential", "kwin-tuh-SEN-shuhl", "Representing the most perfect example of a quality."),
      Word("vivacious", "vi-VAY-shuhs", "Attractively lively and animated."),
      Word("solitude", "SOL-i-tood", "The state of being alone, often by choice."),
      Word("resilience", "ri-ZIL-yuhns", "The capacity to recover quickly from difficulties."),
      Word("wanderlust", "WON-der-lust", "A strong desire to travel and explore the world."),
      Word("nebulous", "NEB-yuh-luhs", "In the form of a cloud or haze; vague."),
      Word("cogent", "KOH-juhnt", "Clear, logical, and convincing."),
      Word("ebullient", "ih-BUUL-yuhnt", "Cheerful and full of energy."),
      Word("aplomb", "uh-PLOM", "Self-confidence or assurance in a demanding situation."),
      Word("sanguine", "SANG-gwin", "Optimistic or positive, especially in a bad situation."),
      Word("alacrity", "uh-LAK-ri-tee", "Brisk and cheerful readiness."),
      Word("munificent", "myoo-NIF-i-suhnt", "Larger or more generous than is usual."),
      Word("perspicacious", "pur-spi-KAY-shuhs", "Having a ready insight into things; shrewd."),
      Word("ardent", "AR-duhnt", "Enthusiastic or passionate."),
      Word("verdant", "VUR-duhnt", "Green with grass or other rich vegetation."),
      Word("effervescent", "ef-er-VES-uhnt", "Vivacious and enthusiastic."),
      Word("intrepid", "in-TREP-id", "Fearless; adventurous."),
      Word("lithe", "lyth", "Thin, supple, and graceful."),
      Word("zenith", "ZEE-nith", "The time at which something is most powerful or successful."),
  )

  private val TRIVIA = listOf(
      Trivia("What is the largest planet in our solar system?", "Jupiter"),
      Trivia("How many bones are in the adult human body?", "206"),
      Trivia("What is the capital of Australia?", "Canberra"),
      Trivia("Which element has the chemical symbol 'O'?", "Oxygen"),
      Trivia("Who painted the Mona Lisa?", "Leonardo da Vinci"),
      Trivia("What is the tallest mountain on Earth?", "Mount Everest"),
      Trivia("How many continents are there?", "Seven"),
      Trivia("What is the largest ocean on Earth?", "The Pacific Ocean"),
      Trivia("In what year did the first human walk on the Moon?", "1969"),
      Trivia("What is the smallest prime number?", "2"),
      Trivia("Which language has the most native speakers?", "Mandarin Chinese"),
      Trivia("What gas do plants absorb from the atmosphere?", "Carbon dioxide"),
      Trivia("What is the hardest natural substance on Earth?", "Diamond"),
      Trivia("How many strings does a standard guitar have?", "Six"),
      Trivia("What is the capital of Japan?", "Tokyo"),
      Trivia("Which planet is known as the Red Planet?", "Mars"),
      Trivia("What is the currency of the United Kingdom?", "Pound sterling"),
      Trivia("How many sides does a hexagon have?", "Six"),
      Trivia("What is the longest river in the world?", "The Nile"),
      Trivia("Who wrote 'Romeo and Juliet'?", "William Shakespeare"),
      Trivia("What is the freezing point of water in Celsius?", "0 degrees"),
      Trivia("Which country is home to the kangaroo?", "Australia"),
      Trivia("What is the largest mammal in the world?", "The blue whale"),
      Trivia("How many colors are in a rainbow?", "Seven"),
      Trivia("What is the capital of Romania?", "Bucharest"),
      Trivia("Which metal is liquid at room temperature?", "Mercury"),
      Trivia("How many minutes are in a full day?", "1440"),
      Trivia("What is the speed of light (approx, km/s)?", "300,000 km/s"),
      Trivia("Which organ pumps blood through the body?", "The heart"),
      Trivia("What is the largest country by land area?", "Russia"),
  )
}
