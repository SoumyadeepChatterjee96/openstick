package org.openstick.apps

data class LinuxApp(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val aptPackage: String,
    val iconName: String
)

object AppCatalogue {
    val apps = listOf(
        LinuxApp(
            id = "chromium",
            name = "Chromium",
            category = "Web Browsers",
            description = "Fast, open-source web browser with desktop extensions support",
            aptPackage = "chromium",
            iconName = "browser"
        ),
        LinuxApp(
            id = "firefox",
            name = "Firefox ESR",
            category = "Web Browsers",
            description = "Privacy-focused desktop web browser with full devtools",
            aptPackage = "firefox-esr",
            iconName = "browser"
        ),
        LinuxApp(
            id = "vscode",
            name = "VS Code (Code-OSS)",
            category = "Development",
            description = "Full featured code editor with extensions, Git, and terminal",
            aptPackage = "code-oss",
            iconName = "code"
        ),
        LinuxApp(
            id = "build_essential",
            name = "C/C++ Build Essential",
            category = "Development",
            description = "GCC, G++, Make, and standard C library headers for compilation",
            aptPackage = "build-essential gdb cmake",
            iconName = "terminal"
        ),
        LinuxApp(
            id = "python",
            name = "Python 3 & Pip",
            category = "Development",
            description = "Python 3 interpreter, pip package manager, and virtualenv",
            aptPackage = "python3 python3-pip python3-venv",
            iconName = "code"
        ),
        LinuxApp(
            id = "nodejs",
            name = "Node.js & NPM",
            category = "Development",
            description = "JavaScript runtime and Node package manager",
            aptPackage = "nodejs npm",
            iconName = "code"
        ),
        LinuxApp(
            id = "git",
            name = "Git Version Control",
            category = "Development",
            description = "Distributed version control system",
            aptPackage = "git",
            iconName = "code"
        ),
        LinuxApp(
            id = "libreoffice",
            name = "LibreOffice",
            category = "Office",
            description = "Complete office productivity suite (Writer, Calc, Impress)",
            aptPackage = "libreoffice",
            iconName = "document"
        ),
        LinuxApp(
            id = "gimp",
            name = "GIMP",
            category = "Graphics",
            description = "Professional image manipulation and photo editing suite",
            aptPackage = "gimp",
            iconName = "image"
        ),
        LinuxApp(
            id = "inkscape",
            name = "Inkscape",
            category = "Graphics",
            description = "Professional vector graphics editor for SVG design",
            aptPackage = "inkscape",
            iconName = "image"
        ),
        LinuxApp(
            id = "vlc",
            name = "VLC Media Player",
            category = "Media",
            description = "Versatile media player supporting virtually all audio and video formats",
            aptPackage = "vlc",
            iconName = "media"
        ),
        LinuxApp(
            id = "htop",
            name = "Htop Monitor",
            category = "Utilities",
            description = "Interactive process viewer and system resource monitor",
            aptPackage = "htop",
            iconName = "monitor"
        ),
        LinuxApp(
            id = "neofetch",
            name = "Neofetch & Fastfetch",
            category = "Utilities",
            description = "Command-line system information tool",
            aptPackage = "neofetch fastfetch",
            iconName = "terminal"
        ),
        LinuxApp(
            id = "tmux",
            name = "Tmux Multiplexer",
            category = "Utilities",
            description = "Terminal multiplexer with split panes and background sessions",
            aptPackage = "tmux",
            iconName = "terminal"
        )
    )
}
