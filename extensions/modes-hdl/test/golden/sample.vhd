-- A comment.
library ieee;
use ieee.std_logic_1164.all;

entity counter is
    port (clk : in std_logic;
          q   : out integer);
end counter;

architecture rtl of counter is
    signal n : integer := 16#FF#;
begin
    process (clk)
    begin
        if rising_edge(clk) then
            n <= n + 1;
        end if;
    end process;
end rtl;
