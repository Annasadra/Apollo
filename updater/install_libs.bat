set /p VERSION=<VERSION
set WALLETDIR=%1
del /S /F /Q %1\bin
del /S /F /Q %1\sbin
del /F /Q %1\*.jar

rem curl --retry 100 -o %1\libs.zip -k https://s3.amazonaws.com/updates.apollowallet.org/libs/ApolloWallet-%VERSION%-libs.zip
rem unzip -o %1\libs.zip -d %1

del /F /Q %1\lib\apl-*
rem rmdir /S /Q %1\lib
rem mkdir %1\lib

rem move /Y %WALLETDIR:~0,-1%\ApolloWallet-%VERSION%-libs\*" %WALLETDIR:~0,-1%\lib"
rem del /S /F /Q %1\ApolloWallet-%VERSION%-libs*
rem mkdir %1\tmpdir

rem curl --retry 100 -o %1\tmpdir\%2.zip -k https://s3.amazonaws.com/updates.apollowallet.org/database/%2-2020-q1.zip
rem del /S /F /Q %userprofile%\.apl-blockchain\apl-blockchain-db\%2
rem unzip -o %1\tmpdir\%2.zip -d %userprofile%\.apl-blockchain\apl-blockchain-db\
rem del /S /F /Q %1\tmpdir\%2.zip







