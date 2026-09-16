/*
 * jpty.c
 *
 * Copyright (C) 2000-2004 Peter Graves
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

/* Feature test macros must be defined before any header is included.
 * _XOPEN_SOURCE exposes grantpt(), unlockpt() and ptsname(); _DEFAULT_SOURCE
 * keeps glibc's BSD extensions visible (ECHOCTL, ECHOKE, ECHOPRT, select()),
 * which _XOPEN_SOURCE would otherwise hide. Other platforms declare all of
 * these by default. */
#ifdef __linux__
#define _XOPEN_SOURCE 600
#define _DEFAULT_SOURCE
#endif

#ifndef __CYGWIN__
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <termios.h>
#endif

#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>

#ifndef __CYGWIN__
static int  write_all(int fd, const char *buf, size_t count);
static void set_noecho(int fd);
static void loop(int fdm);
static int  open_master_pty(char *name, size_t size);
#endif

int main(int argc, char *argv[]) 
{
#ifndef __CYGWIN__
    char slave_name[256];
    pid_t pid;
    int fdm;
#endif
    int i = 1;

    if (argc < 2)
	exit(1);

    /* Check for -cd option. */
    if (!strcmp("-cd", argv[i])) {
        /* Next arg is directory to change to. */
        if (++i < argc) {
            if (chdir(argv[i]) < 0)
                exit(1);
            ++i;
        }
    }

    /* We should not be out of args here! */
    if (i >= argc)
	exit(1);

#ifdef __CYGWIN__
    setenv("TERM", "dumb", 1);
    execvp(argv[i], &argv[i]);
    exit(1); /* Not reached. */
#else
    fdm = open_master_pty(slave_name, sizeof(slave_name));

    if (fdm < 0)
	exit(1);

    pid = fork();

    if (pid < 0)
	exit(1);

    if (pid == 0) { 
        /* Child process. */
        int slave;

        close(fdm);

        if (setsid() < 0)
	    exit(1);

        slave = open(slave_name, O_RDWR);

        if (slave < 0)
	    exit(1);

#ifdef __linux__
        if (ioctl(slave, TIOCSCTTY, NULL) < 0)
	    exit(1);
#endif
        if (dup2(slave, STDIN_FILENO) != STDIN_FILENO)
	    exit(1);
        if (dup2(slave, STDOUT_FILENO) != STDOUT_FILENO)
	    exit(1);
        if (dup2(slave, STDERR_FILENO) != STDERR_FILENO)
	    exit(1);

        if (slave > STDERR_FILENO)
	    close(slave);

        set_noecho(STDIN_FILENO);

        putenv("TERM=dumb");
	
	/* Ignore SIGHUP to work around Linux kernel bug (2.6.9). 
	 * http://lkml.org/lkml/2004/10/21/119 */
	signal(SIGHUP, SIG_IGN);

        execvp(argv[i], &argv[i]);

        /* Not reached. */
        exit(1);
    }

    /* Parent process. */
    loop(fdm);
    exit(0);
#endif
}

#ifndef __CYGWIN__
static void set_noecho(int fd)
{
    struct termios t;
    if (tcgetattr(fd, &t) < 0)
	exit(1);
    t.c_lflag &= ~(ECHO | ECHOCTL | ECHOE | ECHOK | ECHOKE | ECHONL | ECHOPRT);
    t.c_oflag &= ~(ONLCR);
    if (tcsetattr(fd, TCSANOW, &t) < 0)
	exit(1);
}

/* Write the whole buffer. write() may transfer fewer bytes than requested,
 * especially on a pty, so keep going until it's all out. Returns 0 on
 * success, -1 if the write failed. */
static int write_all(int fd, const char *buf, size_t count)
{
    while (count > 0) {
        ssize_t written = write(fd, buf, count);

        if (written < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }

        buf += written;
        count -= written;
    }

    return 0;
}

/* Copy stdin to fdm, copy fdm to stdout. */
static void loop(int fdm)
{
    char buf[1024];
    fd_set fdset;
    int done = 0;

    while (!done) {
        FD_ZERO(&fdset);
        FD_SET(STDIN_FILENO, &fdset);
        FD_SET(fdm, &fdset);

        select(fdm + 1, &fdset, NULL, NULL, NULL);

        if (FD_ISSET(fdm, &fdset)) {
            int i = read(fdm, buf, sizeof(buf));

            if (i > 0) {
                if (write_all(STDOUT_FILENO, buf, i) < 0)
                    done = 1;
            } else
                done = 1;
        }

        if (FD_ISSET(STDIN_FILENO, &fdset)) {
            int i = read(STDIN_FILENO, buf, sizeof(buf));

            if (i > 0) {
                if (write_all(fdm, buf, i) < 0)
                    done = 1;
            } else
                done = 1;
        }
    }
}

static int open_master_pty(char *namebuf, size_t bufsize)
{ 
    int fdm;
    char *sname;

    fdm = open("/dev/ptmx", O_RDWR);
    if (fdm < 0)
	return -1;

    unlockpt(fdm);
    grantpt(fdm);

    sname = ptsname(fdm);
    if (strlen(sname) >= bufsize)
	return -1;
    strcpy(namebuf, sname);

    return fdm;
}
#endif
