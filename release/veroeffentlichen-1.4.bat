@echo off
rem PyPilot 1.4 veroeffentlichen: Push, Tag v1.4, GitHub-Release mit APK (nur Repo maik8407/PyPilot-App)
rem Voraussetzung: gh ist angemeldet (gh auth status)
cd /d "%~dp0.."
git push origin main || goto fehler
git tag v1.4
git push origin v1.4 || goto fehler
gh release create v1.4 "app\build\outputs\apk\release\PyPilot-1.4.apk" --repo maik8407/PyPilot-App --title "PyPilot 1.4" --notes "Siehe CHANGELOG.md, Abschnitt 1.4" --target main || goto fehler
echo.
echo Fertig: https://github.com/maik8407/PyPilot-App/releases/tag/v1.4
pause
exit /b 0
:fehler
echo.
echo FEHLER - bitte Meldung oben lesen.
pause
exit /b 1
