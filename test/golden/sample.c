#include <stdio.h>
#define MAX 0X10

/* Block comment. */
static int count = 0x1f;

#if 0
int disabled(void);
#endif

int main(int argc, char **argv)
{
    // line comment
    const char *s = "hello\n";
    for (int i = 0; i < argc; i++) {
        if (argv[i][0] == '-')
            count++;
        else
            printf("%s %d\n", s, MAX);
    }
    return 0;
}
