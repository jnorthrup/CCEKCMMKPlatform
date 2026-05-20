@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.confix
import borg.trikeshed.charstr.CharStr
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.*

enum class Syntax
interface ConfixLifecycle
typealias ConfixIndex = FacetedRow<Any>

typealias JsonIndex = MetaSeries<Series<Char>, Join<Series<Twin<Int>>, Series<IOMemento>>>
typealias Tree = MetaSeries<Join<Series<Twin<Int>>, Series<IOMemento>>, Series2<Int, Join<IOMemento, Series<Int>>>>
typealias Decoder = MetaSeries<IOMemento, MetaSeries<Series<Char>, MetaSeries<Twin<Int>, Any?>>>
typealias Reifier = MetaSeries<Join<Series<Twin<Int>>, Series<IOMemento>>, MetaSeries<Decoder, Series2<CharStr, Join<IOMemento, Series<Int>>>>>
typealias Path = MetaSeries<MetaSeries<Either<String, Int>, Int>, Series<MetaSeries<Char, Int>>>
typealias Scope = MetaSeries<MetaSeries<Series<Char>, Syntax>, MetaSeries<ConfixLifecycle, MetaSeries<Series<Char>, ConfixIndex>>>
