# A comment.
module Demo
  MASK = 0XFF

  class Greeter
    def greet(name, n = 0x1f)
      s = "Hello, #{name}"
      if n > 0
        puts s
      else
        nil
      end
    end
  end
end
