package app.remotesupport

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTyperTest {
    private val keys = mutableListOf<String>()
    private val typer = TextTyper(object : TextTyper.Sink {
        override fun key(name: String) { keys += name }
    })

    @Test fun plainCommitTypesEachCharacter() {
        typer.commit("hi!")
        assertEquals(listOf("h", "i", "!"), keys)
    }

    @Test fun spaceEnterAndTabUseNamedKeys() {
        typer.commit("a b\n\t")
        assertEquals(listOf("a", "space", "b", "Return", "Tab"), keys)
    }

    @Test fun composingWordIsTypedLiveWithoutRepeats() {
        typer.setComposing("h"); typer.setComposing("he"); typer.setComposing("hel")
        typer.commit("hello ")                         // keyboard commits the finished word + space
        assertEquals(listOf("h", "e", "l", "l", "o", "space"), keys)
    }

    @Test fun autocorrectRewritesOnlyTheChangedTail() {
        typer.setComposing("teh")
        keys.clear()
        typer.commit("the ")                           // "teh" -> "the " : delete "eh", type "he "
        assertEquals(listOf("BackSpace", "BackSpace", "h", "e", "space"), keys)
    }

    @Test fun deletingFromTheComposingWordShrinksIt() {
        typer.setComposing("abc"); keys.clear()
        typer.setComposing("ab")
        assertEquals(listOf("BackSpace"), keys)
    }

    @Test fun deleteSurroundingTextSendsBackspaces() {
        typer.commit("x"); keys.clear()
        typer.deleteBefore(2)
        assertEquals(listOf("BackSpace", "BackSpace"), keys)
    }

    @Test fun finishComposingKeepsTheTextAlreadyTyped() {
        typer.setComposing("ok"); typer.finish(); keys.clear()
        typer.setComposing("ok")                       // a new word that happens to look the same
        assertEquals(listOf("o", "k"), keys)
    }

    @Test fun controlCharactersAndEmojiAreSkipped() {
        typer.type("a\u0001b😀c")
        assertEquals(listOf("a", "b", "c"), keys)
    }
}
