// A comment.
`define WIDTH 8

module counter(clk, q);
    input clk;
    output [7:0] q;
    reg [7:0] q;

    always @(posedge clk) begin
        if (q == 8'hFF)
            q <= 0;
        else
            q <= q + 1; /* step */
    end
endmodule
