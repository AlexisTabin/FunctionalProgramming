package interpreter

import java.io.InputStreamReader


object Main {
  import java.io.{BufferedReader, InputStreamReader}
  val in = new BufferedReader(new InputStreamReader(System.in))
  
  def main(args: Array[String]): Unit = {       
        boucle()
        def boucle(): Unit = {
          print("lisp> ")
          val line = in.readLine
          val lisp = Lisp.string2lisp(line)
          val result = Lisp.evaluate(lisp)
          println(result)
          boucle()
        }
    }
}

object LispCode {
    // TODO: implement the function `reverse` in Lisp.
  // From a list (a, b, c, d) it should compute (d, c, b, a)
  // Write it as a String, and test it in your REPL
  val reverse = """
    def (reverse L acc)
    (if (null? L)
        acc
        (reverse
          (cdr L)
          (cons (car L) acc)
        )
    )

  """

    // TODO: implement the function `differences` in Lisp.
  // From a list (a, b, c, d ...) it should compute (a, b-a, c-b, d-c ...)
  // You might find useful to define an inner loop def
  val differences = """
  def (differences L)
      (      
           def (loop L1 )
                (
                  if (null? (cdr L1)) 
                  nil
                  (cons (- (car (cdr L1)) (car L1)) (loop (cdr L1)))
                )
                (
                  loop (cons 0 L)
                )
          )
  """
  val rebuildList = """
  def (rebuildList L)
  (
      def (helper L)
      (
        if (null? (cdr L))
        nil
        (cons (+ (car L) (car (cdr L)))
        (helper (cons (+ (car L) (car (cdr L))) (cdr (cdr L))))
        )

      )
      (if (null? L)
        nil
        (cons (car L) (helper L))
      )
  )
  """

  val withDifferences: String => String =
    (code: String) => "(" + reverse + " (" + differences + " (" + rebuildList + " " + code + ")))"
}
