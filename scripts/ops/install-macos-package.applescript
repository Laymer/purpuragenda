on run arguments
    if (count of arguments) is not 1 then error "INVALID_INSTALLER_ARGUMENT"
    set installerPath to item 1 of arguments
    do shell script "/usr/sbin/installer -pkg " & quoted form of installerPath & " -target /" with administrator privileges
    return ""
end run
