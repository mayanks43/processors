package org.clulab.odin.debugger

import org.clulab.odin.impl.{TokenExtractor, TokenPattern}

class StackFrame(sourceCode: SourceCode) {

  override def toString: String = s"${getClass.getName}\t$sourceCode"
}

class SpecialFrame(message: String, sourceCode: SourceCode) extends StackFrame(sourceCode) {

  def showMessage(): Unit = println(message)
}

object SpecialFrame {

  def apply(message: String)(implicit line: sourcecode.Line, fileName: sourcecode.FileName, enclosing: sourcecode.Enclosing): SpecialFrame = {
    new SpecialFrame(message, new SourceCode(line, fileName, enclosing))
  }
}

class TokenExtractorFrame(tokenExtractor: TokenExtractor, sourceCode: SourceCode) extends StackFrame(sourceCode) {

  override def toString: String = {
    s"${tokenExtractor.name}\t${tokenExtractor.pattern}\t${super.toString}"
  }
}

object TokenExtractorFrame {

  def apply(tokenExtractor: TokenExtractor)(implicit line: sourcecode.Line, fileName: sourcecode.FileName, enclosing: sourcecode.Enclosing): TokenExtractorFrame = {
    new TokenExtractorFrame(tokenExtractor, new SourceCode(line, fileName, enclosing))
  }
}
