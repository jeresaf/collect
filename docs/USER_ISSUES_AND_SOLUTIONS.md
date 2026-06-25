# User Issues and Possible Solutions

This document outlines user-facing problems that can occur in KoboCollect and the practical actions users, supervisors, or support teams can take. It focuses on operational issues experienced while configuring projects, downloading forms, filling forms, collecting media or location data, saving drafts, and submitting finalized forms.

## Quick Triage

When a user reports a problem, first identify where they are in the workflow:

1. Project setup or login
2. Downloading or updating blank forms
3. Opening or filling a form
4. Capturing media, barcode, location, or external data
5. Saving, finalizing, or editing a form
6. Sending finalized forms
7. Managing projects, forms, or saved submissions

For any issue, collect the exact message shown on screen, the project name, the form name and version if visible, whether the device has internet access, and whether the issue affects one device, one form, or all users.

## Project Setup and Configuration

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| KoboCollect is not configured | The app has no project yet, or a shortcut was created before configuration | Open KoboCollect directly and add a project. If using shortcuts, recreate them after project setup. |
| Invalid URL | Server URL was typed incorrectly or has an unsupported format | Re-enter the full server URL. Confirm spelling, protocol, and path with the project lead. Avoid spaces before or after the URL. |
| Server cannot be reached | No internet, wrong URL, captive Wi-Fi login, VPN/proxy issue, or server unavailable | Test internet in a browser. Try another network. Confirm the URL. Ask the project lead whether the server is online. |
| Secure connection fails | Incorrect HTTPS URL, expired certificate, blocked certificate chain, device date/time wrong | Check device date and time. Confirm the server URL. Try a different network. Ask the server administrator to check the HTTPS certificate. |
| Invalid username or password | Credentials are wrong, changed, expired, or entered with extra spaces | Re-enter credentials carefully. Confirm case, spaces, and account status. Ask the project lead to reset the password if needed. |
| Leading or trailing whitespace not allowed in username/password | User copied credentials with a space before or after them | Delete and type the value manually, or paste then remove extra spaces. |
| Duplicate project warning | A project with the same connection settings already exists | Switch to the existing project unless a separate local copy is intentionally needed. |
| Wrong project selected for form | User opened a form shortcut or link while another project is active | Open KoboCollect, switch to the project that contains the form, then open the form again. |
| QR code does not contain valid settings | Wrong QR code, damaged image, incompatible configuration, or non-settings QR code | Scan the correct project configuration QR code. Improve lighting and focus. Ask the project lead to regenerate the QR code. |
| QR code not found in selected image | Selected image does not contain a readable QR code | Select the correct image, avoid cropped or blurry screenshots, or scan the QR code directly. |
| QR code is missing passwords | Project QR code was generated without admin or server passwords | Tap to configure passwords manually, or ask the project lead for a QR code that includes the required passwords. Treat password-containing QR codes as sensitive. |
| Current settings are corrupt | Imported settings are invalid or existing settings are damaged | Reset project settings from project management, then import a known-good configuration or reconfigure manually. |
| Google Drive/Sheets project cannot be created | Google Drive/Sheets project creation is no longer supported | Use a supported server project. For existing Google Drive/Sheets projects, migrate to the supported workflow recommended by the organization. |

## Permissions

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Activity requires a permission that is not granted | Android permission was denied or revoked | Open Android app settings for KoboCollect and grant the needed permission. Then retry the action. |
| Storage permission denied | App cannot access forms, media, or saved answers | Grant storage/file access permissions if requested. Reopen the app after granting permissions. |
| Precise location permission denied | Android 12+ allows approximate location, but Collect needs precise location for location questions | Grant Precise Location permission in Android settings. Retry the location question. |
| Camera permission denied | User denied camera access | Grant camera permission, then retry photo, barcode, selfie, or video capture. |
| Record audio permission denied | User denied microphone access | Grant microphone permission, then reopen the form if background audio is required. |
| Account permission denied | App cannot access device accounts for Google workflows | Grant account permission or select/configure the needed Google account in server settings. |
| Notification permission denied | User does not see progress or background job notifications | Grant notification permission if the user needs download, update, send, or tracking notifications. |

## Downloading and Updating Blank Forms

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| No blank forms available | No forms downloaded, search/filter hides forms, or project has no assigned forms | Tap Download form / Update form. Clear search terms. Ask the project lead to confirm form assignment. |
| Form list download failed | Network, authentication, server, or response problem | Check connection and credentials. Refresh. If it persists for all users, ask the project lead or server administrator to investigate. |
| Server returned an error status | Server rejected the request or is misconfigured | Retry later. Capture the status code and report it to the project lead. |
| Server provided an invalid response | Server returned unexpected content | Confirm the server URL points to the correct KoboToolbox/ODK endpoint. Report the issue with the exact error. |
| Server did not provide a hash for a form | Form listing is incomplete or generated by an incompatible server | Ask the project lead to republish or fix the form on the server. |
| Form cannot be processed or parsed | Form definition contains invalid XML/XForm content or unsupported constructs | Ask the form designer to validate and republish the form. |
| Disk error while downloading a form | Device storage is full, storage permission is missing, or files are inaccessible | Free storage, grant permissions, restart the app, and download again. |
| Invalid submission URL in downloaded form | Form has a bad or missing submission target | Ask the form designer/project lead to correct the submission URL and republish. |
| Some downloads failed | One or more selected forms/media files failed | Open download results details. Retry failed items on stable internet. Report form-specific failures. |
| Form update failed | Auto-update could not reach the server, authenticate, or write files | Check network, credentials, storage, and permissions. Retry manually with Download form / Update form. |
| Old form versions still appear | Hide old versions may be off or update mode may not match expected workflow | Enable Hide old form versions if appropriate. Review blank form update mode with the project lead. |
| More than one media folder detected | Duplicate media folders exist for a form | Remove the duplicate media folder if the user has file access, or ask support to clean the project files and retry. |

## Opening and Loading Forms

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Unable to parse form | Form is malformed or contains unsupported content | Ask the form designer to validate and republish the form. Delete and re-download the form after it is fixed. |
| Error while loading form | Temporary read problem, corrupted form, missing media/data, or device resource issue | Retry opening. Restart app/device. If only one form fails, re-download it. If it continues, report the form name/version. |
| Corresponding blank form is not present or was deleted | User is trying to edit a draft whose blank form was removed | Re-download the matching blank form. If unavailable, ask the project lead for the same form ID/version. |
| Form cannot be edited once finalized | Form is finalized or encrypted | If changes are needed, use a draft before finalizing. For finalized submissions, follow the organization’s correction/resubmission process. |
| Two different forms have same form ID and version | Duplicate forms were downloaded from different sources or server states | Delete one duplicate blank form after confirming with the project lead which version should be used. |
| Form is too complex for the device | Form calculations, repeats, choices, or expressions exceed device capability | Use a more capable device or ask the form designer to simplify calculations, repeats, or choice lists. |
| Missing or invalid media in question label or choice | Form media file was not downloaded, renamed, removed, or corrupted | Re-download the form and media. Ask the form designer to confirm media filenames and republish if needed. |
| Unsaved changes recovered from savepoint | App/device closed unexpectedly during filling | Review recovered answers carefully, then save as draft or continue. |
| App reports a previous crash | App crashed in the last session | Reopen the affected workflow and confirm data. If repeated, capture steps and report through support channels. |

## Filling Forms and Validation

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Required answer error | User skipped a required question | Enter a valid response before moving on or finalizing. |
| Invalid answer error | Response violates a form constraint such as range, format, or consistency rule | Read the question hint/constraint guidance and correct the response. If the rule seems wrong, report it to the project lead. |
| Validation only appears at the end | Constraint processing is set to defer validation until finalization | Use Check for errors before finalizing. Project lead can change constraint behavior if earlier validation is preferred. |
| User cannot navigate backwards | Access control or form setting prevents backward navigation | Continue forward if intended. If corrections are required, ask the project lead to enable back navigation or edit permissions. |
| User accidentally removes a response | Clear/remove response action was confirmed | Re-enter the answer if still in the form. If already saved/finalized, follow the correction process. |
| User accidentally removes a repeat group | Repeat deletion was confirmed | Recreate the repeat group and re-enter its answers if still editing. |
| User discards a form or changes | User chose discard instead of save | Deleted unsaved changes cannot be recovered unless a savepoint exists. Train users to choose Save as draft before leaving. |
| Calculated values fail to update | Form has problematic calculations or expressions | Retry the page. If repeated, report to the form designer; the form design likely needs correction. |
| External data import fails | Required CSV is missing, empty, malformed, or has conflicting columns | Re-download the form and media. Ask the form designer to include the correct CSV and fix column names/data types. |
| Search/select from external data gives syntax errors | Form search expression is invalid | Report to form designer. User cannot fix this in the app. |
| Identity required | Audit/change tracking requires enumerator identity | Enter the required identity value. Project lead should define the expected format. |
| Reason for changes required | Change tracking requires an explanation | Enter a clear reason for the correction before saving. |
| Invalid email address in metadata settings | User entered malformed email | Correct the email address in User and device identity settings. |

## Media, Files, and External Apps

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Could not start recording | Microphone permission missing, microphone unavailable, or recorder failed | Grant microphone permission. Close other recording apps. Restart the form/device and try again. |
| Microphone already in use | Another app or question is using the microphone | Stop other recordings/calls, close apps using the microphone, then retry. |
| Background audio form will not open without permission | Form requires background audio | Grant microphone permission. Reopen the form. |
| Background audio was disabled and recording discarded | User chose to disable required background recording | Reopen the form to start a new recording if needed. Confirm with supervisor whether the submission remains valid. |
| Must stop recording before leaving screen | Active audio recording is in progress | Stop or save the recording before navigating away. |
| Media could not be attached but exists at a path | Temporary media copy failed | Keep the path shown in the message. Retry attaching. If repeated, check storage space and permissions. |
| Error attaching media | Selected/captured media cannot be copied or read | Try again, choose a different file, free storage, or restart the device. |
| Application returned invalid file type | External file picker/camera/recorder returned a file type the question does not accept | Choose the correct media type or use the built-in capture option. |
| Selected file is not a valid image | User selected a non-image or corrupted image | Select a supported image file or retake the photo. |
| Camera error or camera could not initialize | Camera permission denied, camera in use, hardware unavailable, or device issue | Grant permission, close other camera apps, restart device, or use a different device. |
| Front camera not available | Device lacks front-facing camera | Use rear camera if allowed or a different device. |
| Requested external application is missing | Form expects another app for a reading or action | Install the required app, or manually enter the reading when allowed. |
| External app did not return expected information | External app failed or returned invalid result | Retry. Update or reinstall the external app. Manually enter value if allowed. |
| Failed to play audio clip | Referenced audio is missing or invalid | Re-download form media. Ask form designer to check the audio file. |
| File is missing or invalid | Media file was deleted, not downloaded, or corrupt | Re-download form/media. If it is an answer file, recollect or reattach it. |
| Deleting an answer file is irreversible | User confirmed file deletion | Only delete if certain. If deleted accidentally, capture or attach the file again. |

## Barcode, QR Code, and Sensors

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Barcode scan does not detect code | Poor focus, glare, low light, damaged code, unsupported format | Clean lens, improve lighting, hold steady, fit code inside rectangle, or enter value manually if allowed. |
| Invalid QR code while scanning | Scanned QR is not valid project settings or expected content | Verify the QR code source. Ask the project lead to regenerate it. |
| Bearing cannot be collected | Device lacks accelerometer or magnetic field sensor | Use a device with the required sensors or enter a value another way if the form allows it. |
| Bearing/location reading seems inaccurate | Device compass/GPS is not calibrated or environment interferes | Move away from metal/electronics, calibrate compass, wait for better GPS accuracy, or retry outdoors. |

## Location, Maps, GeoPoint, GeoShape, and GeoTrace

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Location providers are disabled | Device GPS/location services are off | Tap Go to settings and enable location. Return to Collect and retry. |
| Precise location not granted | User granted approximate location only | In Android settings, grant Precise Location for KoboCollect. |
| Trying to get location for a long time | Weak GPS signal, indoor use, disabled network location, or poor device hardware | Move outdoors, wait, enable high accuracy/location services, and avoid obstructed areas. |
| Poor or unacceptable accuracy | Current fix does not meet form accuracy requirement | Wait for accuracy to improve, move to open sky, or use manual placement if allowed by the form. |
| Google Maps unavailable | Google Play Services missing or disabled | Install/update/enable Google Play Services, or choose another basemap/provider in Maps settings if available. |
| Basemap source unavailable | Selected basemap is unsupported on the device | Choose another basemap in Settings > Maps. |
| Reference layer not visible | Layer file missing, invalid, or wrong map extent | Confirm layer file is installed for the project. Zoom to the correct area. Ask support to verify the layer format. |
| GeoShape needs at least 3 points | User tried to save polygon with too few points | Add at least three points before saving. |
| GeoTrace needs at least 2 points | User tried to save line with too few points | Add at least two points before saving. |
| User clears or discards a geo feature | Clear/discard action was confirmed | Recreate the feature before saving the answer. |
| Background location tracking disabled | Form asks for tracking but user turned it off | Enable tracking from the form menu if required. |

## Saving, Drafts, Finalization, and Encryption

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Form save failed | Storage full, permission issue, file access error, or corrupted state | Free storage, grant permissions, retry saving. If repeated, save screenshots/notes and contact support. |
| Error saving recovery point | App could not write temporary savepoint | Free storage and grant storage permissions. Save as draft as soon as possible. |
| User cannot edit after finalizing | Finalized forms are locked, and encrypted forms cannot be edited | Save as draft until ready to submit. If correction is needed, create a new submission or follow supervisor process. |
| Encryption error, form not finalized | Device/form encryption configuration failed | Do not delete the draft. Ask the project lead to verify the form’s encryption key and device support. |
| Phone does not support RSA encryption | Device lacks required encryption support | Use a compatible device or remove encryption requirement if appropriate. |
| Invalid RSA public key | Form encryption key is invalid | Ask form designer to fix the public key and republish the form. |
| Finalization takes a long time | Large media, encryption, complex validation, or slow storage | Keep the app open and device charged. Avoid force-closing. |
| Draft not visible in Send Finalized Form | Draft has not been finalized | Open the draft and finalize/send it from the end screen. |
| Saved form disappeared after send | Delete after send is enabled | This is expected if configured. Confirm uploaded data on server before relying on local deletion. |

## Sending Finalized Forms

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| No forms selected | User tapped send without selecting forms | Select one or more finalized forms, then tap Send Selected. |
| Nothing available to send | No finalized forms exist, or filters show only unsent/sent forms | Finalize drafts first. Use Change View to show sent and unsent forms if needed. |
| No network connection available | Device is offline or network is blocked | Turn on Wi-Fi/mobile data, log into captive Wi-Fi, or move to coverage. |
| Server requires authentication | Credentials missing or rejected | Enter valid username/password. Update server settings if credentials changed. |
| No form was uploaded | All selected submissions failed or upload was canceled | Open upload results, fix the specific issue, and retry. |
| Some uploads failed | Some submissions have network, media, server, auth, or form-specific problems | Review failed item details. Retry on stable internet. If the same form always fails, report it with form name/version. |
| Media upload failed because file does not exist | Answer media was deleted or never attached correctly | Reopen/edit draft if possible and reattach media. If finalized and uneditable, recollect or follow supervisor correction process. |
| Background send running | Auto-send or another send job is active | Wait for the current send to finish, then retry. |
| Auto-send not working | Auto-send disabled, wrong network type, no permissions, or background restrictions | Check Auto send setting, network type, Android battery/background restrictions, and notification/permission settings. |
| Upload succeeds but data not visible on server | Wrong project/server, delayed processing, permissions, or filtering on server | Confirm project and server URL. Refresh server view. Ask server administrator to verify receipt. |

## Google Sheets Specific Issues

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Google account not set | Google Sheets server type selected without an account | Select the correct Google account in Server settings. |
| No Google account selected | User skipped account selection or permission was denied | Add/select a Google account on the device and grant account permission. |
| Access denied | Spreadsheet owner has not granted edit permission | Ask spreadsheet owner to grant edit access to the selected Google account. |
| Missing columns in spreadsheet | Sheet does not contain columns required by the form | Ask the project lead to recreate/update the sheet with all required columns. |
| Encrypted forms cannot be submitted to Google Sheets | Google Sheets workflow does not support encrypted submissions | Submit to a supported server or use a non-encrypted form if appropriate. |
| Missing submission URL or invalid sheet ID | Form does not define a valid Google Sheets submission target | Ask form designer to correct the submission URL or fallback submission URL. |
| No columns found to upload | Form/sheet structure does not provide uploadable columns | Ask form designer/project lead to verify form fields and spreadsheet setup. |
| Google Drive project deprecation warning | Existing project uses Google Drive | Plan migration to a supported server workflow before the functionality is removed. |

## Deleting, Resetting, and Project Management

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Project cannot be deleted because of unsent submissions | Local finalized submissions have not been sent or deleted | Send submissions first, or delete them if approved by supervisor. Then delete the project. |
| Project cannot be deleted because background jobs are running | Download/update/send/delete task is active | Wait for the task to finish and try again. |
| Delete project warning | Deleting project removes blank forms, submissions, and settings permanently | Confirm data is backed up/sent before deleting. Do not delete unless instructed. |
| Google Drive/Sheets project delete warning | Deleted Google Drive/Sheets projects cannot be recreated | Migrate data and settings first. Delete only after explicit approval. |
| Form deletion failed | File is locked, permission denied, storage problem, or deletion already in progress | Wait for active deletion to finish. Restart app/device. Try again. |
| Delete action already in progress | User started another delete before previous one ended | Wait until the first delete completes. |
| Reset application warning | Selected data will be permanently deleted | Use reset only with supervisor approval. Prefer targeted fixes such as re-downloading forms. |
| Old or wrong forms remain on device | Forms not deleted after server changes, update mode manual, or hide old versions off | Delete obsolete blank forms manually or set update mode according to project policy. |

## Device, Storage, and Environment

| Issue users may face | Likely cause | Possible solutions |
| --- | --- | --- |
| Disk or file copy error | Storage full, SD card issue, permission denied, or file path inaccessible | Free space, check SD card, grant permissions, restart device, and retry. |
| Could not create media folder | Storage unavailable or permission denied | Grant storage permissions, free space, check device storage health, then re-download form. |
| Could not delete file manually | File is locked or storage is read-only | Restart device and retry. If using SD card, check write protection or replace card. |
| App is slow or freezes on large forms | Device memory/CPU is limited or form is complex | Close other apps, restart device, use a newer device, or ask form designer to simplify the form. |
| Device date/time causes unexpected errors | Incorrect time can break secure connections and timestamps | Enable automatic date/time and timezone. |
| Battery optimization interrupts background work | Android stops background send, audio, or location tracking | Disable battery optimization for KoboCollect if organization policy allows. Keep device charged. |

## Escalation Checklist

Escalate to the project lead or technical support when:

- The same form fails for multiple users.
- The same server/auth/download/upload error affects multiple devices.
- The app reports parsing, invalid submission URL, invalid RSA key, missing columns, missing CSV, or calculation/update errors.
- A finalized submission cannot be sent after network, credential, and permission checks.
- Required answer, constraint, choice, calculation, or external data behavior appears wrong.
- User data may be lost, duplicated, or submitted to the wrong project.

Include this information in the report:

- Device model and Android version
- KoboCollect version
- Project name
- Form name, ID, and version if available
- Exact error message or screenshot
- Whether the device is online
- Steps the user took before the issue
- Whether other users/devices are affected
