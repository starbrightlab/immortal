/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale

/**
 * Bundled public-domain children's stories for the bedtime tile. All are classic fables
 * (Aesop) and traditional tales, long out of copyright, so they can ship in an
 * open-source APK. Kept short and gentle — they're read aloud via Android TTS while the
 * text shows big on screen, pairing the Portal's screen + speaker for a child's room.
 */
object Stories {

  data class Story(val title: String, val emoji: String, val paragraphs: List<String>)

  private val EN_STORIES =
      listOf(
          Story(
              "The Tortoise and the Hare",
              "🐢",
              listOf(
                  "A speedy hare once made fun of a slow-moving tortoise. Tired of the teasing, the tortoise said, \"Let us have a race.\" The hare laughed, but agreed.",
                  "The fox set them off. The hare dashed almost out of sight at once, then stopped. To show how little he thought of the tortoise, he lay down beside the path to nap.",
                  "The tortoise walked, and walked, and walked. He never stopped, not once, until he reached the finish line.",
                  "The hare woke with a start and ran his hardest — but the tortoise had already won. \"Slow and steady,\" the tortoise smiled, \"wins the race.\"",
              )),
          Story(
              "The Lion and the Mouse",
              "🦁",
              listOf(
                  "A great lion lay asleep in the sun. A little mouse ran across his nose and woke him. The lion caught the mouse in his huge paw.",
                  "\"Please let me go,\" squeaked the mouse, \"and one day I may help you.\" The lion laughed at the thought, but he was kind, and let the little mouse go.",
                  "Not long after, hunters caught the lion in a strong net. He roared and roared, but he could not get free.",
                  "The little mouse heard him. She came and gnawed the ropes, one by one, until the lion was free. \"You see,\" said the mouse, \"even a little friend can be a great friend.\"",
              )),
          Story(
              "The Little Red Hen",
              "🐔",
              listOf(
                  "A little red hen found some grains of wheat. \"Who will help me plant the wheat?\" she asked. \"Not I,\" said the cat, the dog, and the pig. \"Then I will,\" said the hen. And she did.",
                  "When the wheat was tall, she asked, \"Who will help me cut it?\" \"Not I,\" they all said. \"Then I will,\" said the hen. And she did.",
                  "She asked who would help carry it to the mill, and bake the bread. Each time they said, \"Not I.\" Each time the hen said, \"Then I will.\" And she did.",
                  "At last the warm bread was ready. \"Who will help me eat it?\" \"I will!\" they all cried. \"No,\" said the little red hen. \"I planted, and cut, and baked it myself. And I will eat it myself.\" And she did.",
              )),
          Story(
              "The North Wind and the Sun",
              "☀️",
              listOf(
                  "The North Wind and the Sun argued about who was stronger. They saw a traveler walking along, and agreed: whoever could make him take off his coat was the stronger.",
                  "The North Wind blew first. He blew cold and hard. But the harder he blew, the tighter the traveler wrapped his coat around himself.",
                  "Then it was the Sun's turn. The Sun shone gently, warmer and warmer. Soon the traveler grew so warm that he took his coat right off.",
                  "\"Gentleness,\" said the Sun, \"can do what force cannot.\"",
              )),
          Story(
              "The Ant and the Grasshopper",
              "🐜",
              listOf(
                  "All summer long the grasshopper sang and played, while the ants worked hard, carrying food to store away.",
                  "\"Why work so hard?\" laughed the grasshopper. \"Come and sing with me!\" But the ants kept working, gathering grain for the winter.",
                  "When the cold winter came, the grasshopper had nothing to eat. The ants, warm in their nest, had plenty.",
                  "The grasshopper learned that there is a time to play and a time to work — and it is wise to make ready while you can.",
              )),
      )

  private val ES_STORIES =
      listOf(
          Story(
              "La Tortuga y la Liebre",
              "🐢",
              listOf(
                  "Una veloz liebre se burlaba de una lenta tortuga. Cansada de las burlas, la tortuga dijo: \"Hagamos una carrera\". La liebre se rió, pero aceptó.",
                  "El zorro dio la salida. La liebre salió disparada y pronto se perdió de vista. Para demostrar lo poco que le importaba la tortuga, se echó a dormir junto al camino.",
                  "La tortuga caminó y caminó sin parar ni una sola vez hasta llegar a la meta.",
                  "La liebre despertó y corrió con todas sus fuerzas, pero la tortuga ya había ganado. \"Despacio y con constancia,\" sonrió la tortuga, \"se gana la carrera.\"",
              )),
          Story(
              "El León y el Ratón",
              "🦁",
              listOf(
                  "Un gran león dormía al sol. Un pequeño ratón corrió sobre su nariz y lo despertó. El león atrapó al ratón con su enorme garra.",
                  "\"Por favor déjame ir,\" chilló el ratón, \"y algún día te ayudaré.\" El león se rió de la idea, pero fue amable y lo dejó ir.",
                  "Poco después, unos cazadores atraparon al león en una red. Rugió y rugió, pero no podía liberarse.",
                  "El ratón lo escuchó, vino y royó las cuerdas una a una hasta liberar al león. \"Como ves,\" dijo el ratón, \"incluso un pequeño amigo puede ser un gran amigo.\"",
              )),
          Story(
              "La Gallinita Roja",
              "🐔",
              listOf(
                  "Una gallinita roja encontró unos granos de trigo. \"¿Quién me ayudará a plantar el trigo?\" preguntó. \"Yo no,\" dijeron el gato, el perro y el cerdo. \"Entonces lo haré yo,\" dijo la gallina. Y lo hizo.",
                  "Cuando el trigo creció, preguntó: \"¿Quién me ayudará a cortarlo?\" \"Yo no,\" dijeron todos. \"Entonces lo haré yo,\" dijo la gallina. Y lo hizo.",
                  "Preguntó quién la ayudaría a llevarlo al molino y hacer el pan. Cada vez dijeron: \"Yo no.\" Y cada vez la gallina dijo: \"Entonces lo haré yo.\" Y lo hizo.",
                  "Al fin el pan tibio estuvo listo. \"¿Quién me ayudará a comerlo?\" \"¡Yo!\" gritaron todos. \"No,\" dijo la gallinita. \"Yo lo planté, lo corté y lo horneé. Y yo sola me lo comeré.\" Y así lo hizo.",
              )),
          Story(
              "El Viento del Norte y el Sol",
              "☀️",
              listOf(
                  "El Viento del Norte y el Sol discutían sobre quién era más fuerte. Vieron a un viajero e hicieron un trato: quien lograra quitarle el abrigo sería el ganador.",
                  "El Viento del Norte sopló primero con fuerza y frío. Pero cuanto más soplaba, más se envolvía el viajero en su abrigo.",
                  "Luego fue el turno del Sol. El Sol brilló suavemente, cada vez más cálido. Pronto el viajero sintió tanto calor que se quitó el abrigo.",
                  "\"La dulzura,\" dijo el Sol, \"consigue lo que la fuerza no puede.\"",
              )),
          Story(
              "La Cigarra y la Hormiga",
              "🐜",
              listOf(
                  "Durante todo el verano la cigarra cantó y jugó, mientras las hormigas trabajaban duro guardando comida para el invierno.",
                  "\"¿Por qué trabajan tanto?\" se reía la cigarra. \"¡Vengan a cantar conmigo!\" Pero las hormigas siguieron recolectando grano.",
                  "Cuando llegó el frío invierno, la cigarra no tenía nada que comer. Las hormigas, en su cálido hormiguero, tenían de todo.",
                  "La cigarra aprendió que hay tiempo para jugar y tiempo para trabajar, y que es sabio prepararse cuando se puede.",
              )),
      )

  val ALL: List<Story>
    get() = if (Locale.getDefault().language.lowercase().startsWith("es")) ES_STORIES else EN_STORIES

  fun forLanguage(userLang: String?): List<Story> {
    return if (com.immortal.launcher.i18n.I18n.isSpanish(userLang)) ES_STORIES else EN_STORIES
  }
}
