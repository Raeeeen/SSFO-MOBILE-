# SSFO Mobile — Face Recognition DTR System

SSFO Mobile is the **Android mobile application** developed as part of the SSFO Daily Time Record (DTR) system. It works together with the SSFO web dashboard to automate employee attendance recording.

The mobile application uses **face recognition** to verify an employee's identity before recording their attendance. Face recognition was used as an added security measure to help prevent employees from recording attendance for someone else.

## Features

### Face Registration

Employees can register their face through the mobile application for future attendance verification.

### Face-Based DTR Logging

The application uses face recognition to verify an employee and record their time-in and time-out.

### SSFO Web Dashboard Integration

Attendance records from the mobile application are sent to the SSFO web dashboard, where administrators can view employee attendance and manage DTR records.

### Android Application

Built as a native Android application for use on mobile devices.

## Technologies Used

* Android Studio
* Java / Kotlin
* Camera API
* Google ML Kit
* Firebase Authentication
* Firebase Realtime Database

## System Flow

**SSFO Mobile App**
Face Registration → Face Recognition → Employee Verification → Attendance Recorded

**SSFO Web Dashboard**
Attendance Records → Admin Dashboard → DTR Viewing → PDF Printing / Backup

## Related Project

**SSFO DTR Management Website** — The web-based admin dashboard used to view employee attendance records and generate printable PDF copies of DTR records.

## Project Purpose

SSFO Mobile was developed as part of a **capstone project for a government organization** to help automate employee attendance tracking and improve the security of the attendance process.

Instead of relying on manual attendance records, employees could use the mobile application to verify their identity through face recognition and record their attendance digitally.

## Project Status

**No Longer Running**

As of **September 18, 2026**, this mobile application is no longer running or in active use. The Firebase backend used by the application has been shut down, so the app can no longer connect to its backend services.
