package com.opendiyfont.app.core.shell;

interface IRemoteShellService {
    void destroy() = 16777114; // reserved by Shizuku server
    String exec(String script, int timeoutMs) = 1;
}
