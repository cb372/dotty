object Foo {
  type X = implicit () => Int // now ok, used to be: implicit function needs parameters
  def ff: X = () // error: found: Unit, expected: Int

  type Y = erased () => Int // error: empty function may not be erased
  def gg: Y = () // error: found: Unit, expected: Y

  type Z = erased implicit () => Int // error: empty function may not be erased
  def hh: Z = () // error: found: Unit, expected: Int
}